import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo
import AppKit

/// ScreenCaptureKit 로 디스플레이 한 장을 잡아 픽셀버퍼로 흘린다.
///
/// ⚠️ 「화면 기록」 권한이 없으면 SCShareableContent 조회 자체가 실패한다.
/// 터미널에서 실행하면 권한 주체는 **터미널 앱**이다(cmux/Ghostty). 앱을 바꾸면 다시 승인해야 한다.
final class ScreenCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private var stream: SCStream?
    private let videoQueue = DispatchQueue(label: "deck.capture")
    private(set) var displayID: CGDirectDisplayID = 0
    private(set) var displayBounds: CGRect = .zero
    private(set) var outputSize: CGSize = .zero

    var onFrame: ((CVPixelBuffer, CMTime) -> Void)?
    var onStopped: ((String) -> Void)?

    struct DisplayInfo {
        let id: CGDirectDisplayID
        /// 포인트 크기(= CGDisplayBounds). 좌표 환산의 기준.
        let width: Int
        let height: Int
        /// 백킹 픽셀 크기. Retina 는 포인트의 2배다 — 여기까지는 «진짜 디테일»이 있으므로
        /// 포인트 크기를 상한으로 삼으면 해상도를 절반 버리게 된다.
        let pixelWidth: Int
        let pixelHeight: Int
        let isBuiltin: Bool
        let name: String
        /// 포인트 대비 백킹 배율(보통 1.0 또는 2.0).
        var backingScale: CGFloat { width > 0 ? CGFloat(pixelWidth) / CGFloat(width) : 1 }
    }

    static func listDisplays() async throws -> [DisplayInfo] {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        return content.displays.map { d in
            let builtin = CGDisplayIsBuiltin(d.displayID) != 0
            // NSScreen 쪽에 사람이 읽을 이름이 있다.
            let name = NSScreen.screens.first {
                ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == d.displayID
            }?.localizedName ?? "Display \(d.displayID)"
            // SCDisplay.width/height 는 «포인트»다. 백킹 픽셀은 디스플레이 모드에서 따로 읽는다.
            let mode = CGDisplayCopyDisplayMode(d.displayID)
            let pw = mode?.pixelWidth ?? d.width
            let ph = mode?.pixelHeight ?? d.height
            return DisplayInfo(id: d.displayID, width: d.width, height: d.height,
                               pixelWidth: pw, pixelHeight: ph, isBuiltin: builtin, name: name)
        }
    }


    // MARK: - 가상 디스플레이 모드 맞추기

    /// 대상 디스플레이를 «폰 비율 + 작은 포인트 수» 의 **HiDPI** 모드로 바꾼다.
    ///
    /// 왜 필요한가 — 맥 본 화면(1512pt)을 통째로 폰 상단 절반에 욱여넣으면 글자가 물리적으로 절반이 돼
    /// 읽을 수가 없다. 해상도를 «올려서»는 못 고친다. 포인트 수를 **줄여야** UI 가 커진다.
    /// 참고한 원격 앱 영상이 가상 디스플레이를 704x536·896x682 포인트로 쓴 이유가 이것이다.
    ///
    /// HiDPI(백킹 2x) 모드만 고르는 이유: 720x450pt 라도 백킹이 1440x900px 이면 그만큼 진짜 디테일이 있다.
    /// 1x 모드를 고르면 글자는 커지지만 폰이 크게 업스케일해 뭉개진다.
    ///
    /// displayplacer 셸아웃 대신 네이티브 API 를 쓴다 — HiDPI 모드 지정이 확실하고 의존도 없다.
    /// 바꾸기 전 모드를 기억해 뒀다가 종료할 때 되돌리기 위한 보관소.
    nonisolated(unsafe) static var originalModes: [CGDirectDisplayID: CGDisplayMode] = [:]

    /// 기억해 둔 원래 모드로 되돌린다(종료 시 호출).
    static func restoreOriginalModes() {
        for (id, mode) in originalModes {
            var config: CGDisplayConfigRef?
            guard CGBeginDisplayConfiguration(&config) == .success else { continue }
            CGConfigureDisplayWithDisplayMode(config, id, mode, nil)
            if CGCompleteDisplayConfiguration(config, .forSession) != .success {
                CGCancelDisplayConfiguration(config)
            }
        }
        originalModes.removeAll()
    }

    @discardableResult
    static func fitDisplayMode(_ id: CGDirectDisplayID, targetPointWidth: Int, aspect: CGFloat) -> String? {
        let opts = [kCGDisplayShowDuplicateLowResolutionModes: kCFBooleanTrue!] as CFDictionary
        guard let all = CGDisplayCopyAllDisplayModes(id, opts) as? [CGDisplayMode], !all.isEmpty else {
            return nil
        }
        // 🚨 `isUsableForDesktopGUI` 를 반드시 본다. 목록에는 800x500·720x450 같은 작은 HiDPI 모드가
        //    버젓이 들어 있지만 대부분 **usable=false** 이고, 그걸 넣으면
        //    `CGCompleteDisplayConfiguration` 이 **1001(illegalArgument)** 로 조용히 거부한다.
        //    (실측 2026-09-22 DeskPad: HiDPI 26개 중 데스크탑에 쓸 수 있는 16:10 은 840x525 이 최소)
        let usable = all.filter { $0.isUsableForDesktopGUI() && $0.width > 0 && $0.height > 0 }
        let hidpi = usable.filter { $0.pixelWidth >= $0.width * 2 }
        let pool = hidpi.isEmpty ? (usable.isEmpty ? all : usable) : hidpi

        func score(_ m: CGDisplayMode) -> Double {
            let a = Double(m.width) / Double(m.height)
            // 비율이 먼저다 — 어긋나면 레터박스가 생겨 가뜩이나 좁은 화면을 더 버린다.
            let aspectErr = abs(a - Double(aspect)) * 40
            // 그다음 목표 포인트 폭에 얼마나 가까운가(로그 거리 — 비율로 보는 게 맞다).
            let sizeErr = abs(log(Double(m.width) / Double(targetPointWidth)))
            return aspectErr + sizeErr
        }
        guard let best = pool.min(by: { score($0) < score($1) }) else { return nil }

        let cur = CGDisplayCopyDisplayMode(id)
        if let c = cur, c.width == best.width, c.height == best.height,
           c.pixelWidth == best.pixelWidth, c.pixelHeight == best.pixelHeight {
            return "\(best.width)x\(best.height)pt (이미 적용됨)"
        }

        // 바꾸기 «전» 값을 먼저 기억한다 — 복구가 추정이 되면 안 된다.
        if originalModes[id] == nil, let c = cur { originalModes[id] = c }

        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success else { return nil }
        CGConfigureDisplayWithDisplayMode(config, id, best, nil)
        // 🚨 `.permanently` 는 **재부팅해도 남는다**. 원격 도구가 실수로 바꾼 해상도가 영구히 박히면
        //    사용자는 원인을 모른 채 계속 그 상태로 쓴다. `.forSession` 이면 로그아웃/재부팅에 저절로 낫는다.
        guard CGCompleteDisplayConfiguration(config, .forSession) == .success else {
            CGCancelDisplayConfiguration(config)
            return nil
        }
        let before = cur.map { "\($0.width)x\($0.height)pt" } ?? "?"
        return "\(before) → \(best.width)x\(best.height)pt / \(best.pixelWidth)x\(best.pixelHeight)px"
    }

    /// 지정 디스플레이를 outputSize 크기로 캡처 시작.
    /// - Parameter sourceRect: 디스플레이 «안에서» 잘라 보낼 영역(디스플레이 로컬 포인트).
    ///   nil 이면 전체. 폰에서 핀치로 확대하면 이게 좁아지고, 그만큼 원본 픽셀이 스트림을 꽉 채운다
    ///   — 클라에서 확대하는 것과 달리 **뭉개지지 않는다.**
    func start(displayID wanted: CGDirectDisplayID?, outputSize: CGSize, fps: Int,
               showsCursor: Bool, sourceRect: CGRect? = nil) async throws {
        // 🚨 돌고 있는 스트림을 먼저 멈춘다. 전에는 그대로 새 스트림을 만들어서, 뷰포트가 바뀐 재접속이나
        //    디스플레이 전환 때 **캡처가 둘** 돌았다(옛 것은 참조만 잃고 계속 프레임을 뱉는다).
        await stop()
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard !content.displays.isEmpty else { throw DeckError.msg("캡처 가능한 디스플레이가 없다") }

        let display: SCDisplay
        if let w = wanted, let d = content.displays.first(where: { $0.displayID == w }) {
            display = d
        } else if let d = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) {
            // 대상 선택은 main.swift 의 pickDisplay 가 한다. 여기 폴백은 «주 화면»이다 —
            // 예전엔 «비내장 우선»이었고, 두 곳의 판단이 어긋나 외장 모니터를 잡는 사고가 났다.
            display = d
        } else {
            display = content.displays[0]
        }

        displayID = display.displayID
        displayBounds = CGDisplayBounds(display.displayID)
        self.outputSize = outputSize

        let cfg = SCStreamConfiguration()
        cfg.width = Int(outputSize.width)
        cfg.height = Int(outputSize.height)
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        cfg.pixelFormat = kCVPixelFormatType_32BGRA
        cfg.showsCursor = showsCursor           // 참고한 원격 앱처럼 맥 커서가 화면에 같이 보여야 한다
        cfg.queueDepth = 5
        cfg.scalesToFit = true
        cfg.colorSpaceName = CGColorSpace.sRGB
        if let r = sourceRect { cfg.sourceRect = r; cfg.scalesToFit = true }

        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let s = SCStream(filter: filter, configuration: cfg, delegate: self)
        try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: videoQueue)
        try await s.startCapture()
        stream = s
    }

    /// 해상도나 크롭이 바뀌면 그 자리에서 갈아끼운다(재접속 없이).
    func updateOutputSize(_ size: CGSize, fps: Int, showsCursor: Bool, sourceRect: CGRect? = nil) async throws {
        guard let s = stream else { return }
        let cfg = SCStreamConfiguration()
        cfg.width = Int(size.width)
        cfg.height = Int(size.height)
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        cfg.pixelFormat = kCVPixelFormatType_32BGRA
        cfg.showsCursor = showsCursor
        cfg.queueDepth = 5
        cfg.scalesToFit = true
        cfg.colorSpaceName = CGColorSpace.sRGB
        if let r = sourceRect { cfg.sourceRect = r }
        try await s.updateConfiguration(cfg)
        outputSize = size
    }

    func stop() async {
        guard let s = stream else { return }
        try? await s.stopCapture()
        stream = nil
    }

    // MARK: - SCStreamOutput

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, CMSampleBufferIsValid(sampleBuffer) else { return }
        // 화면이 안 바뀐 프레임은 status 가 .complete 가 아니다. 그대로 인코딩하면 대역만 먹는다.
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int,
              let status = SCFrameStatus(rawValue: raw), status == .complete,
              let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        onFrame?(pb, CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    }

    // MARK: - SCStreamDelegate

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStopped?(error.localizedDescription)
    }
}
