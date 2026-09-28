import Foundation
import CoreGraphics
import CoreMedia
import AppKit

// ─────────────────────────────────────────────────────────────────────────────
// deckd — 폴드8 사이버덱의 맥 쪽 서버
//   화면: ScreenCaptureKit → VideoToolbox H.264 → UDP
//   입력: UDP → CGEvent 주입
// ─────────────────────────────────────────────────────────────────────────────

struct Options {
    var bind = "0.0.0.0"
    var port: UInt16 = 8790
    var token = ""
    var fps = 30
    var bitrate = 10_000_000
    /// 조각을 내보내는 **순간 속도** 상한(bps). 0 이면 `bitrate × 3`.
    ///
    /// 평균 비트레이트와 별개다. 키프레임은 한 프레임에 150~200KB 가 나오는데,
    /// 그걸 링크가 감당하는 속도보다 빨리 쏟으면 라우터 큐가 넘쳐 **그 프레임만** 통째로 흘린다.
    /// 델타는 작아서 멀쩡히 도착하므로 「연결·수신 정상, 화면만 깨짐」이 된다.
    var burstBps = 0
    /// 합성 프레임 모드 — **화면을 캡처하지 않고** 정해진 크기의 가짜 프레임을 보낸다.
    ///
    /// 송신 경로(조각화·페이싱·재전송 링·NACK)를 **TCC 권한 없이** 끝단으로 시험하기 위한 이음매다.
    /// 화면 기록 권한은 바이너리 해시에 묶여서, 새로 빌드한 바이너리는 재승인 전까지 캡처를 못 한다
    /// — 그 사이에도 프로토콜은 시험할 수 있어야 한다. 0 이면 끈다(운영 기본값).
    var syntheticKB = 0
    var displayID: CGDirectDisplayID? = nil
    var listDisplays = false
    /// 프로토콜 골든 바이트열을 찍고 끝낸다 — 폰 쪽 Protocol.kt 와 대조용(`run.sh golden`).
    var printGolden = false
    /// INPUT 배치(hex)를 풀어 한 줄로 찍고 끝낸다 — 폰 쪽 인코더와 대조용(`run.sh golden`).
    var decodeInputHex: String? = nil
    /// 입력을 **주입하지 않고** 절대좌표 환산 결과만 로그로 남긴다(검증용).
    /// 사용자가 쓰는 맥에 커서를 쏘지 않고 좌표 수학을 재려고 둔 이음매다 — `--synthetic` 과 같은 발상.
    var dryInput = false
    var showsCursor = true
    var maxWidth = 2048
    var autoResize = false
    var deckPoints = 840
    var verbose = false
}

func parseArgs() -> Options {
    var o = Options()
    var it = CommandLine.arguments.dropFirst().makeIterator()
    while let a = it.next() {
        switch a {
        case "--bind":      o.bind = it.next() ?? o.bind
        case "--port":      o.port = UInt16(it.next() ?? "") ?? o.port
        case "--token":     o.token = it.next() ?? ""
        case "--fps":       o.fps = Int(it.next() ?? "") ?? o.fps
        case "--bitrate":   o.bitrate = Int(it.next() ?? "") ?? o.bitrate
        case "--burst-mbps": o.burstBps = Int((Double(it.next() ?? "0") ?? 0) * 1_000_000)
        case "--synthetic": o.syntheticKB = Int(it.next() ?? "0") ?? 0
        case "--display":   o.displayID = UInt32(it.next() ?? "")
        case "--max-width": o.maxWidth = Int(it.next() ?? "") ?? o.maxWidth
        case "--list-displays": o.listDisplays = true
        case "--print-protocol-golden": o.printGolden = true
        case "--decode-input": o.decodeInputHex = it.next() ?? ""
        case "--dry-input": o.dryInput = true
        case "--no-cursor": o.showsCursor = false
        case "--autoresize": o.autoResize = true
        case "--deck-points": o.deckPoints = Int(it.next() ?? "") ?? o.deckPoints
        case "-v", "--verbose": o.verbose = true
        case "-h", "--help":
            print("""
            deckd — 폴드8 사이버덱 맥 서버

            사용법: deckd [옵션]
              --bind <ip>        바인딩 주소 (기본 0.0.0.0. Tailscale 주소를 주면 그 인터페이스만 연다)
              --port <n>         UDP 포트 (기본 8790)
              --token <str>      공유 토큰. 없으면 ~/.config/deckd/token 을 읽고, 그것도 없으면 생성한다
              --fps <n>          목표 프레임레이트 (기본 30)
              --bitrate <bps>    목표 비트레이트 (기본 10000000)
              --burst-mbps <n>   조각 송신의 «순간 속도» 상한 (기본: 비트레이트의 3배)
                                 같은 랜이면 신경 쓸 필요 없다. 인터넷 너머(다른 망)로 보낼 때
                                 이 값이 업링크보다 크면 키프레임이 통째로 유실된다 — 낮춰라
              --display <id>     처음 캡처할 디스플레이 ID (기본: 주 화면). 폰에서 바꿀 수 있다
              --max-width <px>   스트림 가로 상한 (기본 2048)
              --autoresize       대상 디스플레이를 폰 비율 + 작은 포인트의 HiDPI 모드로 바꾼다
                                 (가상 디스플레이 전용. 맥 본 화면에 쓰면 그 화면도 같이 바뀐다)
              --deck-points <n>  --autoresize 가 노릴 «포인트 가로폭». 작을수록 폰에서 글자가 크다
                                 (기본 840 — DeskPad 에서 실제로 쓸 수 있는 16:10 HiDPI 중 최소. 폰 글자가
                                 14" 맥북에서 보던 것과 거의 같은 물리 크기가 된다)
              --synthetic <KB>   화면을 캡처하지 않고 그 크기의 «가짜 키프레임»을 보낸다(시험용).
                                 조각화·페이싱·NACK 재전송을 TCC 권한 없이 검증한다
              --no-cursor        맥 마우스 커서를 화면에 그리지 않는다
              --list-displays    디스플레이 목록만 출력하고 종료
              --print-protocol-golden  DISPLAYS 패킷 골든 바이트열(hex)을 출력하고 종료
              --decode-input <hex>     INPUT 배치 본문(hex)을 풀어 한 줄로 출력하고 종료
              --dry-input        입력을 주입하지 않고 절대좌표 환산만 로그로 남긴다(검증용)
              -v, --verbose      프레임 단위 로그
            """)
            exit(0)
        default:
            FileHandle.standardError.write("알 수 없는 인자: \(a)\n".data(using: .utf8)!)
            exit(2)
        }
    }
    return o
}

func loadOrCreateToken(_ given: String) -> String {
    if !given.isEmpty { return given }
    let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/deckd")
    let file = dir.appendingPathComponent("token")
    if let s = try? String(contentsOf: file, encoding: .utf8) {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { return t }
    }
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let t = (0..<32).map { _ in "0123456789abcdef".randomElement()! }.reduce(into: "") { $0.append($1) }
    try? t.write(to: file, atomically: true, encoding: .utf8)
    try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    print("🔑 토큰을 새로 만들어 저장했다: \(file.path)")
    return t
}

/// 지금 켜져 있는 디스플레이 id 들(정렬). 꽂고 뽑기 감지용 — 동기이고 싸다.
func activeDisplayIDs() -> [CGDirectDisplayID] {
    var n: UInt32 = 0
    guard CGGetActiveDisplayList(0, nil, &n) == .success, n > 0 else { return [] }
    var ids = [CGDirectDisplayID](repeating: 0, count: Int(n))
    guard CGGetActiveDisplayList(n, &ids, &n) == .success else { return [] }
    return Array(ids.prefix(Int(n))).sorted()
}

func log(_ s: String) {
    let ts = DateFormatter()
    ts.dateFormat = "HH:mm:ss"
    print("[\(ts.string(from: Date()))] \(s)")
    fflush(stdout)
}

// MARK: - 서버 본체

final class DeckServer {
    let opts: Options
    let tag: Data
    let udp: UDPServer
    let capture = ScreenCapture()
    let encoder: H264Encoder
    var injector: InputInjector?

    private var frameId: UInt32 = 0
    private var streamSize = CGSize(width: 1280, height: 800)
    private var clientViewport = CGSize(width: 1080, height: 1080)
    private var streaming = false
    /// 폰이 보고 있는 «화면 안의 사각형»(디스플레이 로컬 포인트). 전체 = 디스플레이 크기.
    private var cropRect: CGRect = .zero
    private var lastViewApply: Date = .distantPast
    private var sessionId: UInt32 = 0
    /// 폰이 고른 디스플레이. nil 이면 [pickDisplay] 의 기본(CLI `--display` → 주 화면).
    /// 🚨 이 값은 **캡처 대상**만 정한다. 모드 변경(`fitDisplay`)에는 절대 쓰지 않는다.
    private var selectedDisplay: CGDirectDisplayID?
    /// 마지막으로 조회한 디스플레이 목록 — DISPLAYS 재전송용(UDP 라 한 번 보낸 것이 유실될 수 있다).
    private var lastDisplays: [ScreenCapture.DisplayInfo] = []
    /// 꽂고 뽑기 감지용. `CGGetActiveDisplayList` 는 동기·저렴해서 1초마다 봐도 된다.
    private var lastActiveIDs: [CGDirectDisplayID] = []
    private var statsTicks = 0
    /// 스트리밍 시작이 실패했을 때(대개 TCC 거부) 재시도를 눌러 둔다.
    /// 클라는 WELCOME 을 못 받으면 매초 HELLO 를 다시 보내므로, 막아두지 않으면 실패가 초당 돈다.
    private var lastStartAttempt: Date = .distantPast
    /// 폰에서 마지막으로 패킷을 받은 때. 폰은 1초마다 PING 을 보낸다.
    /// 🚨 BYE 없이 사라지는 경우(절전·망 끊김)가 흔하다 — 이걸로 끊김을 판정해 캡처를 내린다.
    private var lastHeard: Date = .distantPast
    private let idleTimeout: TimeInterval = 10

    /// 최근 프레임의 조각 패킷을 들고 있는다 — NACK 이 오면 **그 번호만** 다시 보낸다.
    ///
    /// 🚨 **개수가 아니라 시간으로 잡는다.** 처음에 «4개»로 뒀다가 곧바로 틀린 걸 알았다 —
    ///    30fps 면 4개는 **133ms** 인데, NACK 은 탐지 80ms + 편도 지연 뒤에 오고 재시도까지 하면
    ///    400ms 가 넘는다. 그러면 NACK 이 거의 다 「이미 버린 프레임」으로 떨어져 아무 효과가 없다.
    ///    클라가 미완성 프레임을 400ms 에 버리므로 그보다 넉넉한 600ms 를 들고 있는다.
    private var recentFrames: [(fid: UInt32, packets: [Data], at: Date, bytes: Int)] = []
    private let recentFramesTTL: TimeInterval = 0.6
    /// 총량 상한. 키프레임이 연달아 나오는 구간에서 메모리가 부는 것을 막는다.
    private let recentFramesMaxBytes = 4 << 20

    /// 조각 12개를 내보낸 뒤 쉴 시간(µs). **버스트 상한에서 유도한다.**
    /// 전에는 200µs 상수였는데 그게 ≈576Mbps 라 사실상 제한이 없었다.
    private var paceUSec: UInt32 {
        let burst = opts.burstBps > 0 ? opts.burstBps : opts.bitrate * 3
        return UInt32(max(0, min(50_000,
            Double(12 * Proto.maxDatagram * 8) / Double(max(1, burst)) * 1_000_000)))
    }

    // 텔레메트리
    private var sentBytes = 0
    /// NACK 으로 다시 보낸 조각 수(텔레메트리·진단용)
    private var resentFragments = 0
    private var sentFrames = 0
    private var lastEncodeMs: Double = 0
    private var statsTimer: DispatchSourceTimer?

    init(opts: Options, token: String) throws {
        self.opts = opts
        self.tag = Packet.tokenTag(token)
        self.udp = try UDPServer(bindHost: opts.bind, port: opts.port)
        self.encoder = H264Encoder(bitrate: opts.bitrate)
    }

    func run() {
        udp.onPacket = { [weak self] type, data, off in self?.handle(type, data, off) }
        udp.start(expectedTag: tag)

        encoder.onEncoded = { [weak self] annexB, isKey, ms in
            self?.sendVideo(annexB, isKeyframe: isKey, encodeMs: ms)
        }

        capture.onFrame = { [weak self] pb, pts in
            guard let self, self.udp.hasPeer else { return }
            self.encoder.encode(pb, pts: pts)
        }
        capture.onStopped = { reason in
            log("⚠️ 캡처가 멈췄다: \(reason)")
        }

        startStatsTimer()
        log("🎛  deckd 대기 중 — \(opts.bind):\(opts.port) (UDP)")
    }

    private func startStatsTimer() {
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "deck.stats"))
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let bps = self.sentBytes * 8
            let fps = self.sentFrames
            self.sentBytes = 0
            self.sentFrames = 0
            if self.streaming, Date().timeIntervalSince(self.lastHeard) > self.idleTimeout {
                Task { await self.stopStreaming("폰에서 \(Int(self.idleTimeout))초간 응답 없음") }
                return
            }
            // 🚨 세션이 없을 때 STATS 를 보내면 안 된다. 클라가 «수신 중»으로 오판해
            //    HELLO 를 다시 안 보내고, 서버 재시작 때마다 영구 교착이 된다(2026-09-22 실측).
            guard self.udp.hasPeer, self.streaming else { return }
            var w = ByteWriter()
            w.u32(self.sessionId)
            w.u32(UInt32(min(bps, Int(UInt32.max))))
            w.u16(UInt16(min(fps, 65535)))
            w.u16(UInt16(min(Int(self.lastEncodeMs), 65535)))
            w.u16(UInt16(Int(self.streamSize.width)))
            w.u16(UInt16(Int(self.streamSize.height)))
            self.udp.send(Packet.frame(.stats, tokenTag: self.tag, body: w.data))
            self.statsTicks += 1
            let ids = activeDisplayIDs()
            if ids != self.lastActiveIDs {
                let first = self.lastActiveIDs.isEmpty
                self.lastActiveIDs = ids
                if !first { Task { await self.displaysChanged() } }
            } else if self.statsTicks % 5 == 0 {
                self.sendDisplays()
            }
            if self.opts.verbose {
                log("↑ \(String(format: "%.2f", Double(bps)/1_000_000))Mbps \(fps)fps enc \(String(format: "%.1f", self.lastEncodeMs))ms \(Int(self.streamSize.width))x\(Int(self.streamSize.height))")
            }
        }
        t.resume()
        statsTimer = t
    }

    // MARK: 수신 라우팅

    private func handle(_ type: PacketType, _ data: Data, _ off: Int) {
        lastHeard = Date()
        switch type {
        case .hello:
            var r = ByteReader(data, offset: off)
            guard let vw = r.u16(), let vh = r.u16() else { return }
            _ = r.u16() // dpi (현재 미사용)
            let vp = CGSize(width: Int(vw), height: Int(vh))
            // HELLO 는 재전송된다(클라의 keepAlive 가 WELCOME 을 못 받으면 계속 보낸다).
            // 같은 뷰포트로 이미 스트리밍 중이면 WELCOME 만 다시 주고 캡처는 건드리지 않는다.
            if streaming, vp == clientViewport {
                sendWelcome()
                sendDisplays()
                encoder.requestKeyframe()
                return
            }
            clientViewport = vp
            injector?.releaseOwnButton()   // 우리가 누른 채 남은 버튼만 놓는다
            log("👋 접속: \(udp.peerDescription) 뷰포트 \(vw)x\(vh)")
            if opts.syntheticKB > 0 { startSynthetic(); return }
            Task { await self.startStreaming() }

        case .resize:
            var r = ByteReader(data, offset: off)
            guard let vw = r.u16(), let vh = r.u16(), vw > 0, vh > 0 else { return }
            let newVp = CGSize(width: Int(vw), height: Int(vh))
            guard newVp != clientViewport else { return }
            clientViewport = newVp
            log("📐 뷰포트 변경 → \(vw)x\(vh)")
            Task { await self.reconfigure() }

        case .view:
            // 본문은 디스플레이 크기에 대한 «정규화 좌표»(0~65535)다.
            // 포인트로 주고받으면 폰이 맥의 포인트 크기를 알아야 해서 한 번 더 왕복해야 한다.
            var r = ByteReader(data, offset: off)
            guard let nx = r.u16(), let ny = r.u16(), let nw = r.u16(), let nh = r.u16(),
                  nw > 0, nh > 0 else { return }
            let dw = capture.displayBounds.width, dh = capture.displayBounds.height
            guard dw > 0, dh > 0 else { return }
            let k: CGFloat = 65535
            var rect = CGRect(x: (CGFloat(nx) / k) * dw, y: (CGFloat(ny) / k) * dh,
                              width: (CGFloat(nw) / k) * dw, height: (CGFloat(nh) / k) * dh)
            // H.264 는 짝수 크기를 요구하고, 화면 밖으로 새면 캡처가 조용히 빈 프레임을 준다.
            rect.size.width  = max(64, min(rect.width.rounded(),  dw))
            rect.size.height = max(64, min(rect.height.rounded(), dh))
            rect.origin.x = min(max(0, rect.minX.rounded()), dw - rect.width)
            rect.origin.y = min(max(0, rect.minY.rounded()), dh - rect.height)
            guard rect != cropRect else { return }
            cropRect = rect
            // 폰의 핀치는 초당 수십 번 온다 — 캡처 재설정을 그대로 따라가면 스트림이 끊긴다.
            guard Date().timeIntervalSince(lastViewApply) > 0.12 else { return }
            lastViewApply = Date()
            Task { await self.applyCrop() }

        case .selectDisplay:
            var r = ByteReader(data, offset: off)
            guard let id = r.u32() else { return }
            Task { await self.selectDisplay(id) }

        case .input:
            handleInput(data, off)

        case .ping:
            var r = ByteReader(data, offset: off)
            guard let t = r.u64() else { return }
            // 클라가 «지금 눌려 있다고 믿는» 버튼과 대조해 끼인 버튼을 푼다.
            // 떼기 패킷 하나가 UDP 에서 유실되면 그 버튼이 영원히 눌린 채 남기 때문이다.
            if let mask = r.u8() { injector?.reconcileButtons(mask: mask) }
            var w = ByteWriter(); w.u64(t)
            udp.send(Packet.frame(.pong, tokenTag: tag, body: w.data))

        case .keyframeRequest:
            encoder.requestKeyframe()

        case .fragNack:
            // 「그 프레임의 이 조각들만 다시」. 본문: frameId(4) count(2) idx(2)×count
            var r = ByteReader(data, offset: off)
            guard let fid = r.u32(), let n = r.u16(), n > 0 else { return }
            guard let frame = recentFrames.first(where: { $0.fid == fid }) else {
                // 이미 버린 프레임이다 — 되살릴 수 없으니 기준점을 새로 준다.
                if opts.verbose { log("↩︎ NACK fid=\(fid) — 이미 버린 프레임, 키프레임으로 대체") }
                encoder.requestKeyframe()
                return
            }
            let pace = paceUSec
            var sent = 0
            for _ in 0..<n {
                guard let idx = r.u16(), Int(idx) < frame.packets.count else { break }
                let pkt = frame.packets[Int(idx)]
                udp.send(pkt)
                sentBytes += pkt.count
                sent += 1
                // 재전송도 같은 상한을 지킨다 — 안 그러면 복구가 또 버스트가 된다.
                if pace > 0, sent % 12 == 0 { usleep(pace) }
            }
            resentFragments += sent
            if opts.verbose { log("↩︎ NACK fid=\(fid) 조각 \(sent)개 재전송") }

        case .bye:
            log("👋 클라이언트 종료")
            Task { await self.stopStreaming("클라이언트 종료") }

        default:
            break
        }
    }

    private func handleInput(_ data: Data, _ off: Int) {
        guard let inj = injector else {
            if opts.verbose { log("⌨️  INPUT 수신했으나 injector 가 없다(스트리밍 전)") }
            return
        }
        guard let events = InputEvent.decodeBatch(data, offset: off) else {
            if opts.verbose { log("⌨️  INPUT 배치를 못 풀었다(모르는 종류·잘림) — 통째로 버린다") }
            return
        }
        if opts.dryInput {
            // 🧪 주입하지 않는다. 푼 결과(클릭의 모디파이어 포함)와 절대좌표 환산만 찍는다.
            for ev in events {
                if case .moveAbs(let x, let y) = ev {
                    let (p, b) = inj.mapAbsoluteNow(x: x, y: y)
                    log("🧪 dry abs(\(x),\(y)) → 전역 (\(Int(p.x.rounded())),\(Int(p.y.rounded()))) · 화면 원점 (\(Int(b.minX)),\(Int(b.minY))) \(Int(b.width))x\(Int(b.height))")
                } else {
                    log("🧪 dry \(ev.label)")
                }
            }
            return
        }
        for ev in events {
            switch ev {
            case .keyDown(let c, let f):   inj.keyDown(c, flags: f)
            case .keyUp(let c, let f):     inj.keyUp(c, flags: f)
            case .moveRel(let x, let y):   inj.moveRelative(dx: x, dy: y)
            case .moveAbs(let x, let y):   inj.moveAbsolute(x: x, y: y)
            case .mouseDown(let b, let f): inj.mouseDown(b, flags: f)
            case .mouseUp(let b, _):       inj.mouseUp(b)   // 떼기는 누를 때의 모디파이어를 쓴다
            case .scroll(let y, let x):    inj.scroll(dy: y, dx: x)
            case .text(let t):             inj.text(t)
            }
        }
        let trace = events.filter { if case .moveRel = $0 { return false }; if case .keyUp = $0 { return false }; return true }.map(\.label)
        if opts.verbose, !trace.isEmpty { log("⌨️  \(trace.joined(separator: " "))") }
    }

    // MARK: 스트리밍 시작/재설정

    /// 디스플레이 비율을 유지하면서 클라 뷰포트 안에 맞춘 스트림 크기.
    /// 왜곡보다 레터박스를 택한다 — 원격 조작에서 좌표가 어긋나는 것이 더 나쁘다.
    ///
    /// 🚨 상한은 «포인트»가 아니라 **백킹 픽셀**이다. Retina 맥에서 포인트(1512x982)를 상한으로 두면
    ///    실제로 있는 디테일(3024x1964)의 절반을 버리고, 폰이 그걸 다시 업스케일해 흐려진다.
    private func computeStreamSize(display: CGSize, backingScale: CGFloat) -> CGSize {
        let boxW = min(clientViewport.width, CGFloat(opts.maxWidth))
        let boxH = clientViewport.height
        guard display.width > 0, display.height > 0, boxW > 0, boxH > 0 else { return display }
        let scale = min(boxW / display.width, boxH / display.height, max(backingScale, 1.0))
        var w = (display.width * scale).rounded()
        var h = (display.height * scale).rounded()
        w -= w.truncatingRemainder(dividingBy: 2)
        h -= h.truncatingRemainder(dividingBy: 2)
        return CGSize(width: max(w, 160), height: max(h, 120))
    }

    /// [force] 는 폰이 화면을 바꿀 때 — 재시도 억제(3초)는 «실패가 초당 도는 것»을 막으려는 것이지
    /// 사용자의 전환을 막으려는 것이 아니다.
    private func startStreaming(force: Bool = false) async {
        guard force || Date().timeIntervalSince(lastStartAttempt) > 3 else { return }
        lastStartAttempt = Date()
        do {
            // 모드를 먼저 바꾸고 «그 다음에» 크기를 읽어야 한다 — 순서가 바뀌면 옛 해상도로 스트림을 잡는다.
            // 🚨 --autoresize 는 «--display 로 명시한 화면»에만 건다.
            //    비내장 화면을 알아서 고르게 두면 사용자의 **실제 외장 모니터** 해상도를 바꿔 버린다.
            //    (2026-09-22 실측 사고: FlipAction16 이 840x525 로 바뀌고 폰이 그걸 미러링했다.
            //     DeskPad 같은 가상 화면과 진짜 모니터는 «비내장»이라는 점에서 구별되지 않는다.)
            if opts.autoResize {
                if let want = opts.displayID {
                    fitDisplay(want)
                } else {
                    log("⚠️ --autoresize 는 --display <id> 와 함께만 쓴다 — 어느 화면을 바꿀지 명시하지 않으면")
                    log("   실제 외장 모니터 해상도를 바꿔 버릴 수 있다. 이번엔 건너뛴다.")
                    log("   (목록은 --list-displays)")
                }
            }
            let displays = try await ScreenCapture.listDisplays()
            lastDisplays = displays
            guard let target = pickDisplay(displays) else {
                log("❌ 캡처할 디스플레이가 없다"); return
            }
            let dispSize = CGSize(width: target.width, height: target.height)
            streamSize = computeStreamSize(display: dispSize, backingScale: target.backingScale)

            try encoder.configure(width: Int(streamSize.width), height: Int(streamSize.height), fps: opts.fps)
            streamSize = CGSize(width: encoder.width, height: encoder.height)

            cropRect = CGRect(x: 0, y: 0, width: CGFloat(target.width), height: CGFloat(target.height))
            try await capture.start(displayID: target.id, outputSize: streamSize, fps: opts.fps,
                                    showsCursor: opts.showsCursor, sourceRect: cropRect)

            let inj = InputInjector(displayBounds: capture.displayBounds, streamSize: streamSize)
            inj.syncCursorFromSystem()
            injector = inj

            log("🖥  캡처 \(target.name) \(target.width)x\(target.height)pt (백킹 \(target.pixelWidth)x\(target.pixelHeight)) → 스트림 \(Int(streamSize.width))x\(Int(streamSize.height)) @\(opts.fps)fps, \(opts.bitrate/1_000_000)Mbps (버스트 상한 \((opts.burstBps > 0 ? opts.burstBps : opts.bitrate * 3)/1_000_000)Mbps)")

            sessionId = UInt32.random(in: 1...UInt32.max)
            streaming = true
            sendWelcome()
            sendDisplays()
            encoder.requestKeyframe()
        } catch {
            log("❌ 스트리밍 시작 실패: \(error)")
        }
    }

    /// 캡처를 **실제로** 내린다.
    /// 🚨 예전엔 BYE 에서 `streaming = false` 만 하고 SCStream 은 켜 둔 채였다. 그동안 맥은 계속
    ///    «화면 기록 중»이라 넷플릭스(FairPlay) 같은 DRM 영상이 검게 나왔다(2026-09-24 실측).
    private func stopStreaming(_ reason: String) async {
        let wasCapturing = streaming || syntheticTimer != nil
        streaming = false
        sessionId = 0
        injector?.releaseOwnButton()
        injector = nil
        syntheticTimer?.cancel()
        syntheticTimer = nil
        await capture.stop()
        encoder.stop()
        if wasCapturing { log("⏹  캡처 정지 — \(reason)") }
    }

    private func reconfigure() async {
        do {
            // 🚨 모드는 **CLI 로 명시한 화면**에만 바꾼다. 폰이 다른 화면을 골랐는데 여기서
            //    `capture.displayID` 를 넘기면 그 화면(실제 외장 모니터일 수 있다)의 해상도를 바꾼다 —
            //    2026-09-22 사고와 같은 경로다. 판정은 [shouldFit] 한 곳.
            if shouldFit(capture.displayID) { fitDisplay(capture.displayID) }
            let displays = try await ScreenCapture.listDisplays()
            guard let target = displays.first(where: { $0.id == capture.displayID }) else { return }
            let newSize = computeStreamSize(display: CGSize(width: target.width, height: target.height),
                                            backingScale: target.backingScale)
            guard newSize != streamSize else { return }
            try encoder.configure(width: Int(newSize.width), height: Int(newSize.height), fps: opts.fps)
            streamSize = CGSize(width: encoder.width, height: encoder.height)
            cropRect = CGRect(x: 0, y: 0, width: CGFloat(target.width), height: CGFloat(target.height))
            try await capture.updateOutputSize(streamSize, fps: opts.fps,
                                               showsCursor: opts.showsCursor, sourceRect: cropRect)
            injector?.updateGeometry(displayBounds: capture.displayBounds, streamSize: streamSize,
                                     viewRect: globalCrop())

            sendWelcome()
            encoder.requestKeyframe()
            log("♻️  스트림 재설정 → \(Int(streamSize.width))x\(Int(streamSize.height))")
        } catch {
            log("❌ 재설정 실패: \(error)")
        }
    }

    /// 대상 디스플레이를 «폰 비율 + 작은 포인트» HiDPI 모드로 맞춘다.
    /// ⚠️ 가상 디스플레이(DeskPad 등)에 쓰라고 만든 것이다 — 맥 본 화면에 걸면 그 화면과 창 배치가 같이 바뀐다.
    /// 캡처 대상 하나를 고른다.
    /// 🚨 예전에 이 판단이 main.swift 와 Capture.swift 두 곳에 흩어져 있었고, 한쪽만 고쳐서
    ///    «본 화면을 잡는다»고 믿는 동안 실제로는 외장 모니터를 잡고 있었다(2026-09-22 사고).
    ///    이제 여기서만 고르고 그 id 를 캡처에 넘긴다.
    ///    우선순위: **폰이 고른 화면 → CLI `--display` → 주 화면.** 고른 화면이 사라졌으면 다음 순위로.
    private func pickDisplay(_ displays: [ScreenCapture.DisplayInfo]) -> ScreenCapture.DisplayInfo? {
        if let sel = selectedDisplay, let d = displays.first(where: { $0.id == sel }) { return d }
        if let want = opts.displayID, let d = displays.first(where: { $0.id == want }) { return d }
        return displays.first { $0.id == CGMainDisplayID() } ?? displays.first
    }

    /// 이 화면의 **모드(해상도)를 바꿔도 되나.** `--autoresize` 이고 **CLI 로 명시한 바로 그 화면**일 때만.
    /// 폰의 선택·주 화면·«비내장 첫 번째»는 전부 거절한다.
    private func shouldFit(_ id: CGDirectDisplayID) -> Bool {
        guard opts.autoResize, let want = opts.displayID else { return false }
        return id == want
    }

    // MARK: 디스플레이 목록·전환

    /// DISPLAYS 를 보낸다. 목록은 [lastDisplays](스트리밍을 시작할 때·꽂고 뽑을 때 갱신).
    private func sendDisplays() {
        guard udp.hasPeer, streaming, !lastDisplays.isEmpty else { return }
        let mainID = CGMainDisplayID()
        let entries = lastDisplays.map { d -> DisplayList.Entry in
            var f: UInt8 = 0
            if d.isBuiltin { f |= DisplayList.builtin }
            if d.id == mainID { f |= DisplayList.main }
            if d.id == capture.displayID { f |= DisplayList.current }
            return DisplayList.Entry(id: d.id, width: UInt16(clamping: d.width),
                                     height: UInt16(clamping: d.height), flags: f, name: d.name)
        }
        udp.send(Packet.frame(.displays, tokenTag: tag, body: DisplayList.encode(entries)))
    }

    /// 폰이 «이 화면을 보여 달라»고 했다.
    private func selectDisplay(_ id: CGDirectDisplayID) async {
        guard streaming else { return }
        guard let displays = try? await ScreenCapture.listDisplays() else { return }
        lastDisplays = displays
        // 방금 조회한 목록에 있는 것만 받는다 — 폰이 들고 있는 목록은 낡았을 수 있다.
        guard displays.contains(where: { $0.id == id }) else {
            log("⚠️ 폰이 목록에 없는 디스플레이(\(id))를 골랐다 — 무시하고 목록을 다시 보낸다")
            sendDisplays(); return
        }
        guard id != capture.displayID else { sendDisplays(); return }
        selectedDisplay = id
        let name = displays.first { $0.id == id }?.name ?? "?"
        log("🖥  폰이 디스플레이 전환 → \(name) (\(id)) — 캡처 대상만 바꾼다(모드는 그대로)")
        await startStreaming(force: true)
    }

    /// 모니터를 꽂거나 뺐다.
    private func displaysChanged() async {
        guard streaming, let displays = try? await ScreenCapture.listDisplays() else { return }
        lastDisplays = displays
        log("🔌 디스플레이 구성 변경 — \(displays.map { $0.name }.joined(separator: ", "))")
        if !displays.contains(where: { $0.id == capture.displayID }) {
            log("   보던 화면이 사라졌다 — 기본 화면으로 돌아간다")
            selectedDisplay = nil
            await startStreaming(force: true)
        } else {
            sendDisplays()
        }
    }

    private func fitDisplay(_ id: CGDirectDisplayID) {
        guard clientViewport.width > 0, clientViewport.height > 0 else { return }
        let aspect = clientViewport.width / clientViewport.height
        if let applied = ScreenCapture.fitDisplayMode(id, targetPointWidth: opts.deckPoints, aspect: aspect) {
            log("🖥  디스플레이 모드 → \(applied)  (폰 비율 \(String(format: "%.2f", aspect)))")
        } else {
            log("⚠️ 디스플레이 모드를 바꾸지 못했다 — 후보 모드가 없거나 설정이 거부됐다")
        }
    }

    /// 디스플레이 로컬 크롭 → 전역 좌표(디스플레이 원점이 음수일 수 있다).
    private func globalCrop() -> CGRect {
        let b = capture.displayBounds
        guard cropRect.width > 0 else { return b }
        return CGRect(x: b.minX + cropRect.minX, y: b.minY + cropRect.minY,
                      width: cropRect.width, height: cropRect.height)
    }

    /// 핀치/팬으로 바뀐 크롭을 캡처에 반영한다. 스트림 크기는 그대로 두므로
    /// 좁게 자를수록 그 영역이 «원본 픽셀로» 확대돼 들어간다.
    private func applyCrop() async {
        guard streaming else { return }
        do {
            try await capture.updateOutputSize(streamSize, fps: opts.fps,
                                               showsCursor: opts.showsCursor, sourceRect: cropRect)
            injector?.updateGeometry(displayBounds: capture.displayBounds, streamSize: streamSize,
                                     viewRect: globalCrop())
            encoder.requestKeyframe()
            if opts.verbose {
                log("🔍 보는 영역 → (\(Int(cropRect.minX)),\(Int(cropRect.minY))) \(Int(cropRect.width))x\(Int(cropRect.height))pt")
            }
        } catch {
            log("⚠️ 크롭 적용 실패: \(error)")
        }
    }

    private func sendWelcome() {
        var w = ByteWriter()
        w.u32(sessionId)
        w.u16(UInt16(Int(streamSize.width)))
        w.u16(UInt16(Int(streamSize.height)))
        w.u8(1)                       // codec: h264
        w.u16(UInt16(opts.fps))
        udp.send(Packet.frame(.welcome, tokenTag: tag, body: w.data))
    }

    // MARK: 합성 송출(시험용)

    private var syntheticTimer: DispatchSourceTimer?

    /// 캡처 없이 가짜 프레임을 보낸다. 키프레임은 `--synthetic <KB>` 크기, 델타는 2KB.
    /// 내용은 의미가 없다 — 재는 것은 **조각이 다 도착하는가**이지 그림이 아니다.
    private func startSynthetic() {
        guard syntheticTimer == nil else { sendWelcome(); return }
        streaming = true
        streamSize = CGSize(width: 1748, height: 1136)
        sendWelcome()
        log("🧪 합성 모드 — 키프레임 \(opts.syntheticKB)KB (화면을 캡처하지 않는다)")
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "deck.synthetic"))
        t.schedule(deadline: .now() + 0.1, repeating: 1.0 / Double(opts.fps))
        var n = 0
        t.setEventHandler { [weak self] in
            guard let self, self.udp.hasPeer else { return }
            n += 1
            let isKey = n % (self.opts.fps * 2) == 1      // 2초마다 키프레임
            let bytes = isKey ? self.opts.syntheticKB * 1024 : 2048
            self.sendVideo(Data(count: bytes), isKeyframe: isKey, encodeMs: 1)
        }
        t.resume()
        syntheticTimer = t
    }

    // MARK: 영상 송출

    private func sendVideo(_ annexB: Data, isKeyframe: Bool, encodeMs: Double) {
        guard udp.hasPeer else { return }
        lastEncodeMs = encodeMs
        frameId &+= 1
        let fid = frameId

        // VIDEO 본문 머리: frameId(4) fragIdx(2) fragCount(2) flags(1) rsv(1) len(2) = 12
        let fragHeader = 12
        let chunk = Proto.maxBody - fragHeader
        let total = max(1, (annexB.count + chunk - 1) / chunk)
        guard total <= 65535 else { return }

        // 🚨 키프레임을 한 번에 쏟으면 링크가 감당 못 해 «그 프레임만» 통째로 유실된다.
        //    델타 프레임(작다)은 멀쩡히 도착하므로 「연결도 되고 수신도 되는데 화면만 깨진」 형태가 된다.
        //    IDR 이 한 번도 완성되지 않으면 디코더는 영영 시작하지 못한다.
        //
        // ★ 상한을 **목표 비트레이트에서 유도**한다. 전에는 「12조각마다 200µs」라는 상수였는데
        //   그 값이 ≈576Mbps 라 사실상 제한이 없었다(바로 위 주석은 「약 40Mbps」라고 적혀 있었다 —
        //   의도와 구현이 14배 어긋나 있었다). 같은 랜에서는 안 드러나고 **인터넷 너머에서만** 터진다.
        //
        //   2026-09-23 실측(같은 노트북에서 같은 시각, 12초·키프레임 강제):
        //     노트북(같은 랜) 키프레임 완전수신 10/10 · 조각유실   0/775  ( 0.0%)
        //     홈맥(다른 망)   키프레임 완전수신  0/8  · 조각유실 327/1210 (27.0%)
        //   평균 수신은 0.7Mbps 뿐이었다 — 대역폭이 아니라 **순간 버스트**가 원인이다.
        //
        // ⚠️ 이 sleep 은 인코더 콜백 스레드를 막는다. 큰 키프레임 하나에 수십 ms 가 걸리므로
        //    그동안 프레임이 밀린다. 깨진 화면보다 낫다고 보고 택한 값이다.
        let paceEvery = 12                 // 이 개수마다 한 번 쉰다
        let pace = paceUSec

        // 먼저 조각 패킷을 다 만들어 둔다 — NACK 이 오면 그대로 다시 보내려고 들고 있는다.
        var packets: [Data] = []
        packets.reserveCapacity(total)
        var offset = 0
        for idx in 0..<total {
            let n = min(chunk, annexB.count - offset)
            var w = ByteWriter()
            w.u32(fid)
            w.u16(UInt16(idx))
            w.u16(UInt16(total))
            w.u8(isKeyframe ? 1 : 0)
            w.u8(0)
            w.u16(UInt16(n))
            w.bytes(annexB.subdata(in: offset..<(offset + n)))
            packets.append(Packet.frame(.video, tokenTag: tag, body: w.data))
            offset += n
        }
        let frameBytes = packets.reduce(0) { $0 + $1.count }
        recentFrames.append((fid: fid, packets: packets, at: Date(), bytes: frameBytes))
        // 오래된 것부터 버린다 — 시간 먼저, 그 다음 총량.
        let cutoff = Date().addingTimeInterval(-recentFramesTTL)
        recentFrames.removeAll { $0.at < cutoff }
        var held = recentFrames.reduce(0) { $0 + $1.bytes }
        while held > recentFramesMaxBytes, recentFrames.count > 1 {
            held -= recentFrames.removeFirst().bytes
        }

        for (idx, pkt) in packets.enumerated() {
            udp.send(pkt)
            sentBytes += pkt.count
            // 임계를 24 → 8 로 낮춘다. 장면 전환에서는 **델타도** 커져서 같이 터진다.
            if total > 8, pace > 0, idx % paceEvery == paceEvery - 1 { usleep(pace) }
        }
        sentFrames += 1
    }
}

// MARK: - 진입점

let opts = parseArgs()

if opts.printGolden {
    print(DisplayList.goldenHex)
    exit(0)
}
if let hex = opts.decodeInputHex {
    let bytes = stride(from: 0, to: hex.count - 1, by: 2).compactMap {
        UInt8(hex[hex.index(hex.startIndex, offsetBy: $0)...].prefix(2), radix: 16)
    }
    guard let evs = InputEvent.decodeBatch(Data(bytes), offset: 0) else { print("nil"); exit(1) }
    print(evs.map(\.label).joined(separator: " "))
    exit(0)
}

if opts.listDisplays {
    Task {
        do {
            let ds = try await ScreenCapture.listDisplays()
            func pad(_ s: String, _ n: Int) -> String {
                s.count >= n ? s : s + String(repeating: " ", count: n - s.count)
            }
            print(pad("ID", 12) + pad("포인트", 14) + pad("백킹픽셀", 14) + pad("종류", 12) + "이름")
            for d in ds {
                print(pad(String(d.id), 12)
                      + pad("\(d.width)x\(d.height)", 14)
                      + pad("\(d.pixelWidth)x\(d.pixelHeight)", 14)
                      + pad(d.isBuiltin ? "내장" : "외부/가상", 12)
                      + d.name)
            }
        } catch {
            print("❌ 디스플레이 조회 실패 — 「화면 기록」 권한이 있는지 확인하라: \(error)")
            exit(1)
        }
        exit(0)
    }
    dispatchMain()
}

let token = loadOrCreateToken(opts.token)

if !InputInjector.accessibilityGranted() {
    log("⚠️ 「손쉬운 사용」 권한이 없다 — 키보드·마우스 주입이 조용히 무시된다.")
    log("   시스템 설정 → 개인정보 보호 및 보안 → 손쉬운 사용 에서 이 터미널 앱을 허용할 것.")
    InputInjector.promptForAccessibility()
}

// 종료할 때 «내가 바꾼 것»은 되돌린다. 안 그러면 사용자가 원인 모를 해상도로 계속 쓰게 된다.
func installCleanup() {
    for sig in [SIGINT, SIGTERM] {
        signal(sig, SIG_IGN)
        let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
        src.setEventHandler {
            log("🧹 종료 — 바꿨던 디스플레이 모드를 되돌린다")
            ScreenCapture.restoreOriginalModes()
            exit(0)
        }
        src.resume()
        signalSources.append(src)
    }
    atexit { ScreenCapture.restoreOriginalModes() }
}
var signalSources: [DispatchSourceSignal] = []

do {
    let server = try DeckServer(opts: opts, token: token)
    installCleanup()
    server.run()
    log("🔑 토큰 태그 \(Packet.tokenTag(token).map { String(format: "%02x", $0) }.joined())")
    dispatchMain()
} catch {
    log("❌ 기동 실패: \(error)")
    exit(1)
}
