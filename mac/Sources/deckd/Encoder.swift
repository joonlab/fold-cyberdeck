import Foundation
import VideoToolbox
import CoreMedia
import CoreVideo

/// H.264 실시간 인코더. 출력은 Annex-B(길이 접두사가 아니라 0x00000001 시작코드) 바이트열.
///
/// 폰의 MediaCodec 은 Annex-B + in-band SPS/PPS 를 가장 잘 먹는다. 그래서 키프레임마다
/// 파라미터셋을 앞에 다시 붙인다 — 중간에 붙은 클라이언트도 다음 키프레임에서 복구된다.
final class H264Encoder {
    private var session: VTCompressionSession?
    private let queue = DispatchQueue(label: "deck.encode")
    private(set) var width: Int = 0
    private(set) var height: Int = 0
    private var bitrate: Int

    /// (annexB, isKeyframe, 인코딩 소요 ms)
    var onEncoded: ((Data, Bool, Double) -> Void)?

    private var pendingKeyframe = false
    private var encodeStart: [Int64: CFAbsoluteTime] = [:]
    private let startLock = NSLock()

    init(bitrate: Int) { self.bitrate = bitrate }

    func configure(width: Int, height: Int, fps: Int) throws {
        queue.sync {
            if let s = session { VTCompressionSessionInvalidate(s); session = nil }
        }
        // H.264 는 짝수 해상도를 요구한다. 홀수가 들어오면 인코더가 조용히 실패하거나 화면이 밀린다.
        let w = width  - (width  % 2)
        let h = height - (height % 2)
        var s: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(w), height: Int32(h),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil,
            compressionSessionOut: &s
        )
        guard status == noErr, let sess = s else {
            throw DeckError.msg("VTCompressionSessionCreate 실패 (status \(status))")
        }

        func set(_ key: CFString, _ value: CFTypeRef) {
            VTSessionSetProperty(sess, key: key, value: value)
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        // B프레임을 끄지 않으면 지연이 프레임 단위로 쌓인다. 원격 조작에서는 치명적이다.
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_High_AutoLevel)
        set(kVTCompressionPropertyKey_AverageBitRate, NSNumber(value: bitrate))
        // 순간 폭주를 막는다(1초 창에 평균의 1.5배까지).
        set(kVTCompressionPropertyKey_DataRateLimits, [NSNumber(value: bitrate / 8 * 3 / 2), NSNumber(value: 1)] as CFArray)
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, NSNumber(value: fps * 4))
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, NSNumber(value: 4))
        set(kVTCompressionPropertyKey_ExpectedFrameRate, NSNumber(value: fps))
        VTCompressionSessionPrepareToEncodeFrames(sess)

        self.session = sess
        self.width = w
        self.height = h
    }

    func requestKeyframe() { pendingKeyframe = true }

    func setBitrate(_ bps: Int) {
        bitrate = bps
        guard let s = session else { return }
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: bps))
    }

    func encode(_ pixelBuffer: CVPixelBuffer, pts: CMTime) {
        guard let s = session else { return }
        var props: CFDictionary?
        if pendingKeyframe {
            pendingKeyframe = false
            props = [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue!] as CFDictionary
        }
        let key = Int64(pts.value)
        startLock.lock(); encodeStart[key] = CFAbsoluteTimeGetCurrent(); startLock.unlock()

        VTCompressionSessionEncodeFrame(
            s, imageBuffer: pixelBuffer, presentationTimeStamp: pts, duration: .invalid,
            frameProperties: props, infoFlagsOut: nil
        ) { [weak self] status, _, sample in
            guard let self, status == noErr, let sample else { return }
            self.handle(sample, key: key)
        }
    }

    private func handle(_ sample: CMSampleBuffer, key: Int64) {
        startLock.lock()
        let started = encodeStart.removeValue(forKey: key)
        // 오래된 항목이 쌓이지 않게 정리(콜백이 안 온 프레임 대비).
        if encodeStart.count > 120 { encodeStart.removeAll() }
        startLock.unlock()
        let elapsedMs = started.map { (CFAbsoluteTimeGetCurrent() - $0) * 1000 } ?? 0

        guard let db = CMSampleBufferGetDataBuffer(sample) else { return }

        let isKey = !(CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
            .flatMap { ($0 as NSArray).firstObject as? NSDictionary }
            .flatMap { $0[kCMSampleAttachmentKey_NotSync] as? Bool } ?? false)

        var out = Data()
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]

        if isKey, let fmt = CMSampleBufferGetFormatDescription(sample) {
            var count = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            for i in 0..<count {
                var ptr: UnsafePointer<UInt8>?
                var size = 0
                if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: i, parameterSetPointerOut: &ptr, parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr, let p = ptr {
                    out.append(contentsOf: startCode)
                    out.append(p, count: size)
                }
            }
        }

        var lengthAtOffset = 0, totalLength = 0
        var dataPtr: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(db, atOffset: 0, lengthAtOffsetOut: &lengthAtOffset, totalLengthOut: &totalLength, dataPointerOut: &dataPtr) == noErr,
              let base = dataPtr else { return }

        // AVCC(4바이트 길이 접두사) → Annex-B 변환
        var off = 0
        base.withMemoryRebound(to: UInt8.self, capacity: totalLength) { p in
            while off + 4 <= totalLength {
                var nalLen: UInt32 = 0
                memcpy(&nalLen, p + off, 4)
                nalLen = UInt32(bigEndian: nalLen)
                off += 4
                guard nalLen > 0, off + Int(nalLen) <= totalLength else { break }
                out.append(contentsOf: startCode)
                out.append(p + off, count: Int(nalLen))
                off += Int(nalLen)
            }
        }

        guard !out.isEmpty else { return }
        onEncoded?(out, isKey, elapsedMs)
    }

    func stop() {
        queue.sync {
            if let s = session {
                VTCompressionSessionCompleteFrames(s, untilPresentationTimeStamp: .invalid)
                VTCompressionSessionInvalidate(s)
            }
            session = nil
        }
    }
}
