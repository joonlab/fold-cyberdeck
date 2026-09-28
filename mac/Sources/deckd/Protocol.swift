import Foundation
import CryptoKit

// ─────────────────────────────────────────────────────────────────────────────
// DECK 와이어 프로토콜 v1
//
// 모든 패킷은 16바이트 헤더로 시작한다. 정수는 전부 빅엔디안.
//
//   0..3   매직 "DECK"
//   4      버전 (1)
//   5      타입 (PacketType)
//   6..7   예약 (0)
//   8..15  토큰 태그 = SHA256(token) 앞 8바이트
//
// 토큰 태그는 tailnet 안에서 오배달·스캐너를 걸러내는 값싼 관문이다.
// 기밀성을 주지 않는다 — 그 역할은 Tailscale 이 한다.
// ─────────────────────────────────────────────────────────────────────────────

enum Proto {
    static let magic: [UInt8] = Array("DECK".utf8)
    static let version: UInt8 = 1
    static let headerSize = 16
    /// UDP 페이로드 상한. 1500 MTU 에서 IPv4(20)+UDP(8) 를 빼고 Tailscale/WireGuard
    /// 오버헤드(~80)까지 물러선 값. 넘기면 IP 단편화가 일어나 한 조각만 유실돼도 프레임이 통째로 날아간다.
    static let maxDatagram = 1200
    static var maxBody: Int { maxDatagram - headerSize }
}

enum PacketType: UInt8 {
    case hello           = 0x01  // 클라 → 서버: 접속, 뷰포트 통보
    case welcome         = 0x02  // 서버 → 클라: 세션 수립, 스트림 제원
    case bye             = 0x03  // 양방향: 정상 종료
    case video           = 0x10  // 서버 → 클라: H.264 Annex-B 조각
    case input           = 0x20  // 클라 → 서버: 입력 이벤트 묶음
    case ping            = 0x30  // 클라 → 서버
    case pong            = 0x31  // 서버 → 클라 (ping 본문 그대로 반향)
    case stats           = 0x40  // 서버 → 클라: 텔레메트리
    case keyframeRequest = 0x50  // 클라 → 서버: 키프레임 재요청
    /// 클라 → 서버: **그 프레임의 «없는 조각 번호만»** 다시 달라.
    ///   본문: frameId(4) count(2) idx(2)×count
    ///
    /// 왜 있나: 전에는 조각 하나가 없어도 **키프레임 전체**(150~200KB)를 다시 달라고 했다.
    /// 국제 구간(RTT 58ms)에서는 그 재전송도 같이 깨져서, 조일수록 키프레임이 늘어나는
    /// 되먹임이 됐다(2026-09-23 실측: 버스트를 5Mbps 로 낮추자 키프레임이 8 → 11개).
    /// 클라는 **어느 조각이 없는지 정확히 안다** — 그것만 받으면 왕복 한 번으로 끝난다.
    case fragNack        = 0x51
    case resize          = 0x60  // 클라 → 서버: 뷰포트 변경(접기/펴기)
    case view            = 0x61  // 클라 → 서버: 보고 있는 «맥 화면의 어느 사각형»(줌/팬)
    /// 서버 → 클라: 캡처할 수 있는 디스플레이 목록. 본문은 [DisplayList.encode] 참조.
    /// WELCOME 직후, 그리고 모니터를 꽂거나 뺄 때 보낸다.
    case displays        = 0x62
    /// 클라 → 서버: 이 디스플레이를 보여 달라. 본문: id(u32).
    /// 🚨 **캡처 대상만** 바꾼다. 해상도·배치·주 화면은 절대 건드리지 않는다 —
    ///    `--autoresize` 가 «알아서 고른 화면»의 해상도를 바꿔 실제 외장 모니터를 망친 사고가 있었다(2026-09-22).
    case selectDisplay   = 0x63
}

/// DISPLAYS(0x62) 본문.
///
///   u8 count
///   count × { u32 id · u16 width(pt) · u16 height(pt) · u8 flags · u8 nameLen · nameLen × UTF-8 }
///   flags: bit0 내장 · bit1 주 화면 · bit2 지금 보여 주는 화면
///
/// ⚠️ 폰 쪽 `DisplayList`(Protocol.kt)와 **바이트 단위로 같아야 한다.** 한쪽만 고치면 조용히 어긋난다.
///    그래서 [golden] 을 양쪽에 두고 `run.sh golden` 이 대조한다.
enum DisplayList {
    struct Entry: Equatable {
        let id: UInt32
        let width: UInt16
        let height: UInt16
        let flags: UInt8
        let name: String
    }
    static let builtin: UInt8 = 1 << 0
    static let main: UInt8    = 1 << 1
    static let current: UInt8 = 1 << 2
    /// 한 패킷에 넣는 상한. 이름 63바이트 기준 항목당 76바이트라 8개면 넉넉히 한 조각이다.
    static let maxEntries = 8
    static let maxNameBytes = 63

    static func encode(_ entries: [Entry]) -> Data {
        var w = ByteWriter()
        let list = Array(entries.prefix(maxEntries))
        w.u8(UInt8(list.count))
        for e in list {
            w.u32(e.id); w.u16(e.width); w.u16(e.height); w.u8(e.flags)
            let name = utf8Prefix(e.name, maxNameBytes)
            w.u8(UInt8(name.count)); w.bytes(name)
        }
        return w.data
    }

    /// 글자 경계에서 자른다 — 바이트 중간에서 자르면 폰이 깨진 글자를 받는다.
    private static func utf8Prefix(_ s: String, _ limit: Int) -> Data {
        var out = Data()
        for ch in s {
            let b = Data(String(ch).utf8)
            if out.count + b.count > limit { break }
            out.append(b)
        }
        return out
    }

    /// 양쪽이 대조하는 표본. 한글 이름으로 UTF-8 길이(글자 수 ≠ 바이트 수)까지 잰다.
    static let goldenEntries: [Entry] = [
        Entry(id: 1, width: 1512, height: 982, flags: builtin | main | current, name: "Built-in Retina Display"),
        Entry(id: 0x0000_A1B2, width: 1920, height: 1200, flags: 0, name: "FlipAction16"),
        Entry(id: 0xFFFF_FFFE, width: 840, height: 525, flags: 0, name: "가상 화면"),
    ]
    static var goldenHex: String { encode(goldenEntries).map { String(format: "%02x", $0) }.joined() }
}

enum InputKind: UInt8 {
    case keyDown      = 1  // u16 keyCode, u32 flags
    case keyUp        = 2  // u16 keyCode, u32 flags
    case mouseMoveRel = 3  // i16 dx, i16 dy
    case mouseMoveAbs = 4  // u16 x, u16 y  (스트림 픽셀 좌표)
    case mouseDown    = 5  // u8 button (0=left,1=right,2=middle)
    case mouseUp      = 6  // u8 button
    case scroll       = 7  // i16 dy, i16 dx
    case text         = 8  // u16 len, UTF-8  (IME 우회 직접 입력)
    /// 모디파이어를 실은 클릭(⌘-클릭·⇧-클릭). 폰은 **모디파이어가 켜져 있을 때만** 이 종류를 쓴다 —
    /// 그래서 옛 deckd 는 이걸 모르는 종류로 보고 배치를 통째로 버린다(엉뚱하게 파싱해 헛클릭하지 않는다).
    case mouseDownMods = 9  // u8 button, u32 flags
    case mouseUpMods   = 10 // u8 button, u32 flags
}

/// INPUT 배치를 푼 결과. 주입(`handleInput`)과 `--dry-input`·`--decode-input` 이 **같은 디코더**를 쓴다.
enum InputEvent: Equatable {
    case keyDown(UInt16, UInt32), keyUp(UInt16, UInt32)
    case moveRel(Int16, Int16), moveAbs(UInt16, UInt16)
    case mouseDown(UInt8, UInt32), mouseUp(UInt8, UInt32)
    case scroll(Int16, Int16), text(String)

    /// 클릭에 실을 수 있는 비트는 **⇧ ^ ⌥ ⌘ 넷뿐**이다. Fn·CapsLock·장치 비트는 버린다.
    static let clickModifierMask: UInt32 = 0x0002_0000 | 0x0004_0000 | 0x0008_0000 | 0x0010_0000

    /// 배치 본문(`u8 count` + 이벤트들). 모르는 종류나 잘린 필드를 만나면 **nil** — 반쯤 푼 배치를 쏘지 않는다.
    static func decodeBatch(_ data: Data, offset: Int) -> [InputEvent]? {
        var r = ByteReader(data, offset: offset)
        guard let count = r.u8() else { return nil }
        var out: [InputEvent] = []
        for _ in 0..<count {
            guard let raw = r.u8(), let kind = InputKind(rawValue: raw) else { return nil }
            switch kind {
            case .keyDown:      guard let c = r.u16(), let f = r.u32() else { return nil }; out.append(.keyDown(c, f))
            case .keyUp:        guard let c = r.u16(), let f = r.u32() else { return nil }; out.append(.keyUp(c, f))
            case .mouseMoveRel: guard let x = r.i16(), let y = r.i16() else { return nil }; out.append(.moveRel(x, y))
            case .mouseMoveAbs: guard let x = r.u16(), let y = r.u16() else { return nil }; out.append(.moveAbs(x, y))
            case .mouseDown:    guard let b = r.u8() else { return nil }; out.append(.mouseDown(b, 0))
            case .mouseUp:      guard let b = r.u8() else { return nil }; out.append(.mouseUp(b, 0))
            case .mouseDownMods:
                guard let b = r.u8(), let f = r.u32() else { return nil }; out.append(.mouseDown(b, f & clickModifierMask))
            case .mouseUpMods:
                guard let b = r.u8(), let f = r.u32() else { return nil }; out.append(.mouseUp(b, f & clickModifierMask))
            case .scroll:       guard let y = r.i16(), let x = r.i16() else { return nil }; out.append(.scroll(y, x))
            case .text:
                guard let n = r.u16(), let b = r.take(Int(n)), let s = String(data: b, encoding: .utf8) else { return nil }
                out.append(.text(s))
            }
        }
        return out
    }

    static func modsLabel(_ f: UInt32) -> String {
        var s = ""
        if f & 0x0004_0000 != 0 { s += "^" }
        if f & 0x0008_0000 != 0 { s += "⌥" }
        if f & 0x0002_0000 != 0 { s += "⇧" }
        if f & 0x0010_0000 != 0 { s += "⌘" }
        return s
    }

    /// 로그·골든 대조용 한 줄 표기.
    var label: String {
        switch self {
        case .keyDown(let c, let f): return "key↓0x\(String(c, radix: 16))/f\(f)"
        case .keyUp(let c, let f):   return "key↑0x\(String(c, radix: 16))/f\(f)"
        case .moveRel(let x, let y): return "rel(\(x),\(y))"
        case .moveAbs(let x, let y): return "abs(\(x),\(y))"
        case .mouseDown(let b, let f): return "btn↓\(b)\(Self.modsLabel(f))"
        case .mouseUp(let b, let f):   return "btn↑\(b)\(Self.modsLabel(f))"
        case .scroll(let y, let x):  return "scroll(\(y),\(x))"
        case .text(let s):           return "text(\(s))"
        }
    }
}

// MARK: - 바이트 읽기/쓰기

struct ByteWriter {
    private(set) var data = Data()
    mutating func u8(_ v: UInt8)   { data.append(v) }
    mutating func u16(_ v: UInt16) { data.append(UInt8(truncatingIfNeeded: v >> 8)); data.append(UInt8(truncatingIfNeeded: v)) }
    mutating func i16(_ v: Int16)  { u16(UInt16(bitPattern: v)) }
    mutating func u32(_ v: UInt32) { for s in stride(from: 24, through: 0, by: -8) { data.append(UInt8(truncatingIfNeeded: v >> UInt32(s))) } }
    mutating func u64(_ v: UInt64) { for s in stride(from: 56, through: 0, by: -8) { data.append(UInt8(truncatingIfNeeded: v >> UInt64(s))) } }
    mutating func bytes(_ b: Data) { data.append(b) }
    mutating func bytes(_ b: [UInt8]) { data.append(contentsOf: b) }
}

struct ByteReader {
    private let d: [UInt8]
    private var i: Int
    init(_ data: Data, offset: Int = 0) { d = [UInt8](data); i = offset }
    var remaining: Int { max(0, d.count - i) }
    mutating func u8() -> UInt8? { guard i < d.count else { return nil }; defer { i += 1 }; return d[i] }
    mutating func u16() -> UInt16? {
        guard i + 2 <= d.count else { return nil }; defer { i += 2 }
        return UInt16(d[i]) << 8 | UInt16(d[i+1])
    }
    mutating func i16() -> Int16? { u16().map { Int16(bitPattern: $0) } }
    mutating func u32() -> UInt32? {
        guard i + 4 <= d.count else { return nil }; defer { i += 4 }
        return (0..<4).reduce(UInt32(0)) { $0 << 8 | UInt32(d[i + $1]) }
    }
    mutating func u64() -> UInt64? {
        guard i + 8 <= d.count else { return nil }; defer { i += 8 }
        return (0..<8).reduce(UInt64(0)) { $0 << 8 | UInt64(d[i + $1]) }
    }
    mutating func take(_ n: Int) -> Data? {
        guard n >= 0, i + n <= d.count else { return nil }; defer { i += n }
        return Data(d[i..<(i+n)])
    }
}

// MARK: - 헤더

struct PacketHeader {
    let type: PacketType
    let tokenTag: Data
}

enum Packet {
    /// SHA256(token) 앞 8바이트.
    static func tokenTag(_ token: String) -> Data {
        Data(SHA256.hash(data: Data(token.utf8)).prefix(8))
    }

    static func frame(_ type: PacketType, tokenTag: Data, body: Data) -> Data {
        var w = ByteWriter()
        w.bytes(Proto.magic)
        w.u8(Proto.version)
        w.u8(type.rawValue)
        w.u16(0)
        w.bytes(tokenTag)
        w.bytes(body)
        return w.data
    }

    /// 헤더를 검증하고 (타입, 본문 시작 오프셋) 을 돌려준다. 토큰이 틀리면 nil.
    static func parse(_ data: Data, expectedTag: Data) -> (PacketType, Int)? {
        guard data.count >= Proto.headerSize else { return nil }
        let b = [UInt8](data)
        guard b[0] == Proto.magic[0], b[1] == Proto.magic[1],
              b[2] == Proto.magic[2], b[3] == Proto.magic[3],
              b[4] == Proto.version,
              let t = PacketType(rawValue: b[5]) else { return nil }
        let tag = Data(b[8..<16])
        // 상수시간 비교까지는 필요 없다(태그는 비밀이 아니라 관문이다) — 다만 길이는 맞춰서 본다.
        guard tag == expectedTag else { return nil }
        return (t, Proto.headerSize)
    }
}
