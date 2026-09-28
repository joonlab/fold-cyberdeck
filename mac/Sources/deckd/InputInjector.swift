import Foundation
import CoreGraphics
import ApplicationServices

/// CGEvent 로 맥에 키보드·마우스 입력을 주입한다.
///
/// ⚠️ 「손쉬운 사용(Accessibility)」 권한이 없으면 **예외 없이 조용히 무시된다.**
/// 이 랩의 원칙대로, 보냈다 ≠ 들어갔다 — 그래서 기동 시 권한을 명시적으로 확인하고 크게 알린다.
final class InputInjector {
    private let src: CGEventSource?
    /// 캡처 중인 디스플레이의 전역 좌표 원점·크기. 절대 좌표 변환에 쓴다.
    private var displayBounds: CGRect
    /// 스트림 해상도(폰에 보내는 픽셀 크기). 절대 좌표는 이 좌표계로 들어온다.
    private var streamSize: CGSize
    /// 폰이 «지금 보고 있는» 화면 영역(전역 좌표). 줌/팬 하면 이게 좁아진다.
    /// 절대 좌표는 스트림 → 이 사각형 → 화면 순으로 환산해야 커서가 손끝과 맞는다.
    private var viewRect: CGRect
    private let lock = NSLock()

    /// 마우스 상대이동 누적 위치. 트랙볼은 델타만 보내므로 서버가 커서 위치를 들고 있어야 한다.
    private var cursor: CGPoint

    init(displayBounds: CGRect, streamSize: CGSize) {
        src = CGEventSource(stateID: .hidSystemState)
        // 가속 곡선이 이중으로 먹지 않도록 주입 이벤트는 시스템 마우스 상태와 분리한다.
        src?.setLocalEventsFilterDuringSuppressionState(
            [.permitLocalMouseEvents, .permitLocalKeyboardEvents, .permitSystemDefinedEvents],
            state: .eventSuppressionStateSuppressionInterval
        )
        // 억제 창을 0 으로. 기본값(0.25초)이면 주입 직후 사람이 실제 마우스를 만져도 먹지 않는다.
        src?.localEventsSuppressionInterval = 0
        self.displayBounds = displayBounds
        self.streamSize = streamSize
        self.viewRect = displayBounds
        self.cursor = CGPoint(x: displayBounds.midX, y: displayBounds.midY)
    }

    func updateGeometry(displayBounds: CGRect, streamSize: CGSize, viewRect: CGRect) {
        lock.lock(); defer { lock.unlock() }
        self.displayBounds = displayBounds
        self.streamSize = streamSize
        self.viewRect = viewRect
    }

    static func accessibilityGranted() -> Bool {
        AXIsProcessTrusted()
    }

    /// 권한 요청 다이얼로그를 띄운다(미승인일 때만).
    static func promptForAccessibility() {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
    }

    // MARK: - 키보드

    func keyDown(_ code: UInt16, flags: UInt32) { key(code, down: true, flags: flags) }
    func keyUp(_ code: UInt16, flags: UInt32) {
        key(code, down: false, flags: flags)
        if flags != 0 { clearStickyFlags() }
    }

    /// 🚨 posting 한 키의 모디파이어 플래그가 **이벤트 소스 상태에 남는다.**
    /// 실제 키보드는 «모디파이어를 떼는» flagsChanged 가 따로 나오는데 우리는 그걸 안 보내기 때문이다.
    /// 남아 있으면 **그 뒤에 만드는 마우스 이벤트가 그 플래그를 물려받아** Control+좌클릭,
    /// 즉 macOS 에서는 **우클릭**이 된다 — 「탭했는데 우클릭이 된다」의 정체였다(2026-09-22 실측:
    /// `^Fn→` 한 번에 소스 상태가 0x20840000 으로 고착).
    /// 모디파이어 키 하나를 flags=0 으로 «떼면» 상태 전체가 지워진다(실측).
    private func clearStickyFlags() {
        guard let e = CGEvent(keyboardEventSource: src, virtualKey: 0x3B /* kVK_Control */, keyDown: false)
        else { return }
        e.flags = []
        e.post(tap: .cghidEventTap)
    }

    private func key(_ code: UInt16, down: Bool, flags: UInt32) {
        guard let e = CGEvent(keyboardEventSource: src, virtualKey: CGKeyCode(code), keyDown: down) else { return }
        e.flags = CGEventFlags(rawValue: UInt64(flags))
        e.post(tap: .cghidEventTap)
    }

    /// 유니코드 문자열을 그대로 밀어 넣는다. 한글 조합처럼 가상키코드로 표현하기 곤란한 입력용.
    /// (macOS IME 를 거치지 않으므로 조합 중간 상태가 없다 — 완성된 문자만 보낼 것)
    func text(_ s: String) {
        guard !s.isEmpty else { return }
        let utf16 = Array(s.utf16)
        // 한 이벤트에 너무 많이 실으면 누락된다. 20자 단위로 끊는다.
        for chunk in stride(from: 0, to: utf16.count, by: 20).map({ Array(utf16[$0..<min($0+20, utf16.count)]) }) {
            guard let down = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: true),
                  let up   = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: false) else { continue }
            down.flags = []; up.flags = []
            down.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
            up.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
            down.post(tap: .cghidEventTap)
            up.post(tap: .cghidEventTap)
        }
    }

    // MARK: - 마우스

    func moveRelative(dx: Int16, dy: Int16) {
        lock.lock()
        // 줌이 들어가면 화면상 1px 이 실제로는 더 짧은 거리다 — 배율을 반영하지 않으면
        // 확대했을 때 커서가 손끝보다 훨씬 빨리 달린다.
        let k = displayBounds.width > 0 ? viewRect.width / displayBounds.width : 1
        var p = cursor
        p.x = min(max(p.x + CGFloat(dx) * k, displayBounds.minX), displayBounds.maxX - 1)
        p.y = min(max(p.y + CGFloat(dy) * k, displayBounds.minY), displayBounds.maxY - 1)
        cursor = p
        lock.unlock()
        postMove(to: p)
    }

    /// 스트림 픽셀 → 전역 화면 좌표. **순수 함수**로 둔다 — 실제 주입과 `--dry-input` 검증이
    /// 같은 식을 쓰게(같은 판단을 두 곳에 두면 어긋난다). 원점이 음수인 배치(왼쪽·위에 둔 모니터)도
    /// `viewRect` 가 전역 좌표라 그대로 맞는다.
    static func mapAbsolute(x: UInt16, y: UInt16, streamSize: CGSize, viewRect: CGRect) -> CGPoint {
        let sx = streamSize.width  > 0 ? viewRect.width  / streamSize.width  : 1
        let sy = streamSize.height > 0 ? viewRect.height / streamSize.height : 1
        // 🚨 **보는 영역 안쪽으로 가둔다.** 스트림 끝(x=W, y=H)은 환산하면 maxX·maxY 로, 그 화면
        //    **바로 바깥 한 픽셀**이다. 위에 둔 외장(원점 -243,-1200)에서 (W,H) 가 (1677, 0) 이 되어
        //    y=0 = **내장 화면 맨 윗줄**로 클릭이 샜다(2026-09-23 --dry-input 실측). 상대이동은 이미 가뒀다.
        let px = min(max(viewRect.minX + CGFloat(x) * sx, viewRect.minX), viewRect.maxX - 1)
        let py = min(max(viewRect.minY + CGFloat(y) * sy, viewRect.minY), viewRect.maxY - 1)
        return CGPoint(x: px, y: py)
    }

    /// 지금 기하로 환산만 한다(주입 없음). `--dry-input` 용.
    func mapAbsoluteNow(x: UInt16, y: UInt16) -> (CGPoint, CGRect) {
        lock.lock(); defer { lock.unlock() }
        return (Self.mapAbsolute(x: x, y: y, streamSize: streamSize, viewRect: viewRect), displayBounds)
    }

    func moveAbsolute(x: UInt16, y: UInt16) {
        lock.lock()
        let p = Self.mapAbsolute(x: x, y: y, streamSize: streamSize, viewRect: viewRect)
        cursor = p
        lock.unlock()
        postMove(to: p)
    }

    /// 🚨 macOS 마우스 주입의 함정 — 2026-09-22 실측 (macOS 26.6.2)
    ///
    /// | 방법 | 커서가 목표에 가나 |
    /// |---|---|
    /// | `CGWarpMouseCursorPosition` | ✅ 정확 |
    /// | `mouseDown` / `mouseUp` 이벤트 | ✅ 안 밀린다 |
    /// | `mouseMoved` / `mouseDragged` 이벤트 | ❌ **밀린다** |
    ///
    /// posting 한 이동 이벤트는 «현재 위치 → 이벤트 좌표» 의 델타에 시스템 포인터 가속을 먹인다.
    /// (400,300) 을 보내면 (554.9,440.6) 에 떨어진다. `source=nil`·`privateState`·델타 0 고정·
    /// warp 선행 — **네 가지 모두 결과가 소수점까지 같았다.** 즉 우회로가 없다.
    /// 그래서 위치는 항상 warp 로 잡고, 이벤트는 «앱에게 알리기 위해서만» 보낸다.
    /// 이벤트 자신의 location 필드는 그대로 전달되므로 앱의 히트테스트는 정확하다.
    private func postMove(to p: CGPoint) {
        guard pressed >= 0 else {
            // 단순 이동: warp 만. 이동 이벤트를 보태면 오히려 좌표가 깨진다.
            CGWarpMouseCursorPosition(p)
            return
        }
        // 드래그: 앱이 받을 좌표는 이벤트에 실어 보내고, 시스템 커서는 warp 로 되잡는다.
        let (type, button): (CGEventType, CGMouseButton) = switch pressed {
            case 1: (.rightMouseDragged, .right)
            case 2: (.otherMouseDragged, .center)
            default: (.leftMouseDragged, .left)
        }
        if let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: button) {
            e.setIntegerValueField(.mouseEventDeltaX, value: 0)
            e.setIntegerValueField(.mouseEventDeltaY, value: 0)
            e.flags = pressedFlags   // 전엔 비워 둬서 소스 상태를 물려받았다 — 누를 때 값으로 고정
            e.post(tap: .cghidEventTap)
        }
        CGWarpMouseCursorPosition(p)
    }

    /// 현재 눌려 있는 버튼(없으면 -1). 드래그 판정에 쓴다.
    private var pressed: Int = -1
    /// 누를 때 실은 모디파이어(⌘-클릭 등). 드래그·떼기가 **같은 값**을 쓴다 — ⌥-드래그(복사)가 중간에 풀리지 않게.
    private var pressedFlags: CGEventFlags = []
    /// 더블클릭 판정용.
    private var lastClickAt: Date = .distantPast
    private var clickCount: Int64 = 1

    /// `flags` 는 디코더가 이미 ⇧^⌥⌘ 로 걸러 준 값이다([InputEvent.clickModifierMask]).
    func mouseDown(_ button: UInt8, flags: UInt32 = 0) {
        lock.lock(); let p = cursor; lock.unlock()
        CGWarpMouseCursorPosition(p)   // 눌리는 지점을 확실히 맞춘다(이벤트는 커서를 안 옮긴다)
        let now = Date()
        clickCount = now.timeIntervalSince(lastClickAt) < 0.35 ? min(clickCount + 1, 3) : 1
        lastClickAt = now
        pressed = Int(button)
        pressedFlags = CGEventFlags(rawValue: UInt64(flags & InputEvent.clickModifierMask))
        let (t, b): (CGEventType, CGMouseButton) = switch button {
            case 1: (.rightMouseDown, .right)
            case 2: (.otherMouseDown, .center)
            default: (.leftMouseDown, .left)
        }
        guard let e = CGEvent(mouseEventSource: src, mouseType: t, mouseCursorPosition: p, mouseButton: b) else { return }
        // 🚨 **항상 명시한다.** 비워 두면 소스에 고착된 Control 을 물려받아 좌클릭이 우클릭이 된다(9/22 사고).
        //    모디파이어는 폰이 «이 클릭에» 실어 보낸 것만 — 그 외엔 [] 다.
        e.flags = pressedFlags
        e.setIntegerValueField(.mouseEventClickState, value: clickCount)
        e.post(tap: .cghidEventTap)
    }

    func mouseUp(_ button: UInt8) {
        lock.lock(); let p = cursor; lock.unlock()
        CGWarpMouseCursorPosition(p)
        pressed = -1
        let f = pressedFlags
        pressedFlags = []
        let (t, b): (CGEventType, CGMouseButton) = switch button {
            case 1: (.rightMouseUp, .right)
            case 2: (.otherMouseUp, .center)
            default: (.leftMouseUp, .left)
        }
        guard let e = CGEvent(mouseEventSource: src, mouseType: t, mouseCursorPosition: p, mouseButton: b) else { return }
        e.flags = f
        e.setIntegerValueField(.mouseEventClickState, value: clickCount)
        e.post(tap: .cghidEventTap)
        if !f.isEmpty { clearStickyFlags() }   // keyUp 과 같은 이유 — 다음 이벤트가 물려받지 않게
    }

    func scroll(dy: Int16, dx: Int16) {
        guard let e = CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 2,
                              wheel1: Int32(dy), wheel2: Int32(dx), wheel3: 0) else { return }
        e.flags = []            // Shift 가 묻으면 세로 스크롤이 가로로 바뀐다
        e.post(tap: .cghidEventTap)
    }

    /// 클라가 보고한 «눌려 있는 버튼» 과 대조해, 서버만 눌렀다고 믿는 버튼을 놓아 준다.
    /// 떼기 패킷이 UDP 에서 유실되면 버튼이 영원히 눌린 채 남는다 — 그 뒤로는 모든 조작이 이상해진다.
    func reconcileButtons(mask: UInt8) {
        let p = pressed
        guard p >= 0 else { return }
        if (mask & (1 << UInt8(p))) == 0 { mouseUp(UInt8(p)) }
    }

    /// 새 세션을 깨끗하게 시작한다 — **내가 누른 버튼만** 놓는다.
    ///
    /// 처음엔 `CGEventSourceButtonState` 로 «눌려 있는 모든 버튼»을 놓게 짰는데 그건 틀렸다.
    /// 내가 누르지 않은 버튼(사용자의 실제 마우스, 다른 도구)까지 건드리게 되고,
    /// 사용자가 쓰고 있는 화면에 예상 못 한 이벤트를 쏘는 셈이 된다.
    /// 우리가 책임질 것은 «우리 떼기 패킷이 유실돼 남은 버튼» 하나뿐이다.
    func releaseOwnButton() {
        guard pressed >= 0 else { return }
        mouseUp(UInt8(pressed))
    }

    /// 현재 커서를 실제 시스템 커서 위치와 맞춘다(기동 시 1회).
    func syncCursorFromSystem() {
        if let e = CGEvent(source: nil) {
            lock.lock(); cursor = e.location; lock.unlock()
        }
    }
}
