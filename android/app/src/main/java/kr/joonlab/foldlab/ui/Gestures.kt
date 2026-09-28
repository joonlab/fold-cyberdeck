package kr.joonlab.foldlab.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kr.joonlab.foldlab.input.MacKey
import kotlin.math.abs

/**
 * 손가락 제스처 판정 — **스트림과 트랙패드가 이 한 곳을 쓴다.**
 *
 * 전에는 같은 판정이 `MainActivity.streamGestures` 와 `Trackpad.TrackpadSurface` 두 군데에 있었고,
 * 그 사이에서 이미 값이 어긋나 있었다(세 손가락 임계 70px vs 48px). 같은 판단을 두 파일에 두면
 * 반드시 어긋난다 — 이번 프로젝트에서 그 부류로 이미 두 번 데였다(`--autoresize` 사고 포함).
 * 차이가 필요한 것은 [GestureProfile] 로 **명시해서** 갈라 둔다.
 *
 * 테스트가 보는 창구는 [GestureSink] 하나다. `adb shell input` 은 멀티터치를 못 보내고
 * `sendevent` 는 root 가 필요하므로(이 기기 Permission denied), 검증은 Compose 계측 테스트가
 * `performTouchInput { down(0,..); down(1,..) }` 로 진짜 멀티포인터 이벤트를 흘려 한다.
 * → `app/src/androidTest/.../GestureTest.kt`
 */

/** 지금 무엇으로 판정됐나. 트랙패드는 이걸 가운데 글자로 보여준다. */
enum class GestureMode { Idle, Cursor, Scroll, Zoom, Mac }

/** 한 손가락으로 무엇을 하나 — 두 면의 유일한 본질적 차이. */
enum class OneFinger {
    /** 스트림: 손가락 아래 창을 스크롤한다(맥 스크롤은 포커스가 아니라 커서 아래로 간다). */
    ScrollUnderFinger,
    /** 트랙패드: 커서를 상대이동시킨다. */
    MoveCursor
}

/** 두 손가락으로 무엇을 하나. */
enum class TwoFinger {
    /** 스트림: 늘 «확대 + 보는 영역 이동». 두 동작이 서로 싸우지 않는다. */
    ZoomAndPan,
    /** 트랙패드: 맥과 같게 줌 **또는** 스크롤 하나로 고정한다. */
    LatchZoomOrScroll
}

/**
 * @param threeFingerSlop 세 손가락 판정 거리(px). 면이 크면 크게 준다 —
 *        작은 트랙패드에서 70px 는 손가락이 면을 벗어난다.
 * @param zoomLevel 현재 확대 배율. 확대 중에는 손끝보다 커서·스크롤이 빨리 달리지 않게 감속한다.
 */
data class GestureProfile(
    val oneFinger: OneFinger,
    val twoFinger: TwoFinger,
    val threeFingerSlop: Float,
    val sensitivity: Float = 1f,
    val invertScroll: Boolean = false,
    val zoomLevel: Float = 1f,
    val tapEnabled: Boolean = true
) {
    companion object {
        /** 스트림 면(맥 화면). 면이 크므로 세 손가락 판정도 넉넉히. */
        fun stream(invertScroll: Boolean, zoomLevel: Float, tapEnabled: Boolean) = GestureProfile(
            oneFinger = OneFinger.ScrollUnderFinger,
            twoFinger = TwoFinger.ZoomAndPan,
            threeFingerSlop = 70f,
            invertScroll = invertScroll,
            zoomLevel = zoomLevel,
            tapEnabled = tapEnabled
        )

        /** 노트북식 트랙패드 스트립. */
        fun trackpad(sensitivity: Float, zoomLevel: Float) = GestureProfile(
            oneFinger = OneFinger.MoveCursor,
            twoFinger = TwoFinger.LatchZoomOrScroll,
            threeFingerSlop = 48f,
            sensitivity = sensitivity,
            zoomLevel = zoomLevel
        )
    }
}

/** 제스처가 바깥으로 내보내는 것 전부. 구현은 하나도 필수가 아니다. */
interface GestureSink {
    /** 스크롤 직전, 커서를 손가락 자리로 옮긴다. 면 기준 좌표를 그대로 준다. */
    fun placeCursor(at: Offset) {}
    fun scroll(dy: Int, dx: Int) {}
    fun moveRel(dx: Int, dy: Int) {}
    /** @param fx,fy 면 크기 대비 이동 «비율» — 면 크기가 달라도 같게 먹는다. */
    fun zoom(change: Float, fx: Float, fy: Float) {}
    /** macOS 가상 키코드(↑↓←→) 하나. */
    fun macGesture(macKeyCode: Int) {}
    fun tap(at: Offset) {}
    fun mode(m: GestureMode) {}
}

/** 줌으로 볼 만큼 배율이 변했나. 이보다 작으면 «같이 밀기»로 본다. */
private const val ZOOM_LATCH = 0.06f

/** 스크롤로 볼 만큼 같이 밀었나 — touchSlop 의 몇 배인가. */
private const val PAN_LATCH_SLOP = 1.5f

/**
 * 제스처를 붙인다. 스트림·트랙패드 양쪽이 이 하나를 쓴다.
 *
 * 🚨 `pointerInput` 의 key 를 [profile] 로 주면 **핀치 도중 제스처가 죽는다.**
 * 확대할 때마다 `zoomLevel` 이 바뀌고, key 가 바뀌면 Compose 가 핸들러 코루틴을 취소·재시작한다.
 * 다시 시작한 `awaitFirstDown` 은 «이미 내려가 있는» 손가락을 못 잡으므로(내려가는 순간만 잡는다)
 * 손을 떼기 전까지 아무 이벤트도 못 받는다 — 핀치가 한 칸 움직이고 멈춘다.
 * 그래서 key 는 `Unit` 으로 고정하고, 변하는 값은 [rememberUpdatedState] 로 매 이벤트 다시 읽는다.
 */
@Composable
fun Modifier.deckGestures(profile: GestureProfile, sink: GestureSink): Modifier {
    val p = rememberUpdatedState(profile)
    val s = rememberUpdatedState(sink)

    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)

                var maxN = 1
                var mode = GestureMode.Idle
                var movedOne = 0f          // 한 손가락 누적 이동(슬롭 판정용)
                var placed = false         // 스크롤 전 커서를 옮겼나
                var threeAccum = Offset.Zero
                var threeFired = false
                var zoomAccum = 1f         // 두 손가락 래치 판정용
                var panAccum = Offset.Zero
                var remX = 0f; var remY = 0f   // 정수로 보내고 남은 소수

                val w = size.width.coerceAtLeast(1).toFloat()
                val h = size.height.coerceAtLeast(1).toFloat()

                fun setMode(m: GestureMode) { if (mode != m) { mode = m; s.value.mode(m) } }

                while (true) {
                    val ev = awaitPointerEvent()
                    val n = ev.changes.count { it.pressed }
                    if (n == 0) break
                    if (n > maxN) maxN = n
                    val cur = p.value
                    val pan = ev.calculatePan()

                    // ── 세 손가락: 맥 제스처. 한 제스처에 한 번만 쏜다.
                    if (maxN >= 3) {
                        setMode(GestureMode.Mac)
                        ev.changes.forEach { it.consume() }
                        if (!threeFired) {
                            threeAccum += pan
                            val slop = cur.threeFingerSlop
                            if (abs(threeAccum.x) > slop || abs(threeAccum.y) > slop) {
                                threeFired = true
                                // 맥 트랙패드와 같은 방향 — 왼쪽으로 쓸면 오른쪽 스페이스로 간다.
                                s.value.macGesture(
                                    if (abs(threeAccum.x) > abs(threeAccum.y))
                                        (if (threeAccum.x < 0) MacKey.RIGHT else MacKey.LEFT)
                                    else (if (threeAccum.y < 0) MacKey.UP else MacKey.DOWN)
                                )
                            }
                        }
                        continue
                    }

                    // ── 두 손가락
                    if (maxN >= 2) {
                        ev.changes.forEach { it.consume() }
                        // 하나를 떼면 그 제스처는 멈춘다(맥 트랙패드와 같다).
                        // 남은 한 손가락의 이동이 줌·스크롤로 새면 화면이 튄다.
                        if (n < 2) continue
                        val z = ev.calculateZoom()
                        when (cur.twoFinger) {
                            TwoFinger.ZoomAndPan -> {
                                setMode(GestureMode.Zoom)
                                s.value.zoom(z, pan.x / w, pan.y / h)
                            }
                            TwoFinger.LatchZoomOrScroll -> {
                                if (mode != GestureMode.Zoom && mode != GestureMode.Scroll) {
                                    // 🚨 판정은 «한 번만». 매 프레임 다시 판정하면
                                    //    줌 도중 스크롤로 튀어 화면이 요동친다.
                                    zoomAccum *= z
                                    panAccum += pan
                                    if (abs(zoomAccum - 1f) > ZOOM_LATCH) setMode(GestureMode.Zoom)
                                    else if (panAccum.getDistance() > viewConfiguration.touchSlop * PAN_LATCH_SLOP)
                                        setMode(GestureMode.Scroll)
                                }
                                when (mode) {
                                    GestureMode.Zoom -> s.value.zoom(z, pan.x / w, pan.y / h)
                                    GestureMode.Scroll -> {
                                        remX += pan.x; remY += pan.y
                                        val dx = remX.toInt(); val dy = remY.toInt()
                                        if (dx != 0 || dy != 0) {
                                            remX -= dx; remY -= dy
                                            s.value.scroll(dy, dx)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                        continue
                    }

                    // ── 한 손가락. 슬롭을 넘기 전에는 소비하지 않는다 — 탭이 죽는다.
                    movedOne += pan.getDistance()
                    if (movedOne <= viewConfiguration.touchSlop) continue
                    ev.changes.forEach { it.consume() }

                    when (cur.oneFinger) {
                        OneFinger.ScrollUnderFinger -> {
                            setMode(GestureMode.Scroll)
                            if (!placed) {
                                placed = true
                                ev.changes.firstOrNull()?.position?.let { s.value.placeCursor(it) }
                            }
                            val k = (if (cur.invertScroll) -1f else 1f) / cur.zoomLevel
                            remX += pan.x * k; remY += pan.y * k
                            val dx = remX.toInt(); val dy = remY.toInt()
                            if (dx != 0 || dy != 0) { remX -= dx; remY -= dy; s.value.scroll(dy, dx) }
                        }
                        OneFinger.MoveCursor -> {
                            setMode(GestureMode.Cursor)
                            remX += pan.x * cur.sensitivity; remY += pan.y * cur.sensitivity
                            val dx = remX.toInt(); val dy = remY.toInt()
                            if (dx != 0 || dy != 0) { remX -= dx; remY -= dy; s.value.moveRel(dx, dy) }
                        }
                    }
                }
                setMode(GestureMode.Idle)
            }
        }
        .pointerInput(Unit) {
            // 🚨 `onDoubleTap` 을 달지 않는다. 달면 더블탭 후보를 기다리느라 **모든 단일 탭이**
            //    더블탭 대기시간(약 300ms)만큼 늦게 나간다 — 원격 조작에서 클릭이 0.3초씩
            //    밀리는 건 체감이 크다. 게다가 두 번째 탭이 onTap 으로 안 나와서 맥에
            //    **더블클릭을 보낼 방법이 없어진다**(맥의 더블클릭은 «빠른 두 번의 클릭»이다).
            //    → 탭은 매번 즉시 내보내고, 더블클릭 판정은 deckd 가 한다
            //      (InputInjector 가 0.35초 안의 연속 클릭에 clickState 2·3 을 세운다).
            //    줌을 되돌리는 건 텔레메트리의 ⤢ 칩이 맡는다(1 → 1.5 → 2 → 3 → 1 순환).
            detectTapGestures(onTap = { off -> if (p.value.tapEnabled) s.value.tap(off) })
        }
}
