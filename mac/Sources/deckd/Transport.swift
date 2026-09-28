import Foundation
import Darwin

/// UDP 단일 소켓 서버.
///
/// Network.framework 대신 BSD 소켓을 쓴다 — 특정 인터페이스(Tailscale 주소)에만 바인딩하는 것과
/// 단편화 한계를 직접 통제하는 것이 이 앱의 핵심 제약이라, 추상화가 오히려 방해가 된다.
final class UDPServer {
    private let fd: Int32
    private let queue = DispatchQueue(label: "deck.udp.recv")
    private var running = true

    /// 마지막으로 말을 건 클라이언트. 사이버덱은 1:1 이라 단일 피어로 충분하다.
    private let peerLock = NSLock()
    private var peer: sockaddr_in?
    private var peerLastSeen: Date = .distantPast

    var onPacket: ((PacketType, Data, Int) -> Void)?

    init(bindHost: String, port: UInt16) throws {
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw DeckError.msg("소켓 생성 실패: \(String(cString: strerror(errno)))") }

        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        // 송신 버퍼를 키운다. 키프레임 한 장이 수십 조각으로 한꺼번에 나가므로 기본값이면 EAGAIN 이 뜬다.
        var sndbuf: Int32 = 1 << 21
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        if bindHost == "0.0.0.0" {
            addr.sin_addr.s_addr = INADDR_ANY
        } else {
            guard inet_pton(AF_INET, bindHost, &addr.sin_addr) == 1 else {
                throw DeckError.msg("바인딩 주소를 해석할 수 없다: \(bindHost)")
            }
        }
        let rc = withUnsafePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else { throw DeckError.msg("바인딩 실패 \(bindHost):\(port) — \(String(cString: strerror(errno)))") }
    }

    func start(expectedTag: Data) {
        queue.async { [weak self] in
            guard let self else { return }
            var buf = [UInt8](repeating: 0, count: 65535)
            while self.running {
                var from = sockaddr_in()
                var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
                let n = withUnsafeMutablePointer(to: &from) { p in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        recvfrom(self.fd, &buf, buf.count, 0, sa, &fromLen)
                    }
                }
                guard n > 0 else {
                    if n < 0 && errno != EINTR { usleep(2000) }
                    continue
                }
                let data = Data(buf[0..<n])
                guard let (type, off) = Packet.parse(data, expectedTag: expectedTag) else {
                    continue  // 토큰 불일치·쓰레기 패킷은 조용히 버린다
                }
                self.peerLock.lock()
                self.peer = from
                self.peerLastSeen = Date()
                self.peerLock.unlock()
                self.onPacket?(type, data, off)
            }
        }
    }

    var hasPeer: Bool {
        peerLock.lock(); defer { peerLock.unlock() }
        guard peer != nil else { return false }
        return Date().timeIntervalSince(peerLastSeen) < 10
    }

    var peerDescription: String {
        peerLock.lock(); defer { peerLock.unlock() }
        guard var p = peer else { return "—" }
        var s = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        inet_ntop(AF_INET, &p.sin_addr, &s, socklen_t(INET_ADDRSTRLEN))
        return "\(String(cString: s)):\(UInt16(bigEndian: p.sin_port))"
    }

    @discardableResult
    func send(_ data: Data) -> Bool {
        peerLock.lock()
        guard var to = peer else { peerLock.unlock(); return false }
        peerLock.unlock()
        let sent: Int = data.withUnsafeBytes { raw in
            withUnsafePointer(to: &to) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    sendto(fd, raw.baseAddress, raw.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        return sent == data.count
    }

    func stop() {
        running = false
        close(fd)
    }
}

struct DeckError: Error, CustomStringConvertible {
    let description: String
    static func msg(_ s: String) -> DeckError { DeckError(description: s) }
}
