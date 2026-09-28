package kr.joonlab.foldlab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.joonlab.foldlab.input.MacKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 멀티터치 제스처 검증.
 *
 * **왜 계측 테스트인가**: `adb shell input` 은 단일 포인터만 보낸다
 * (`motionevent <DOWN|UP|MOVE|CANCEL> <x> <y>` — 포인터 id 가 없다).
 * `sendevent` 로 `/dev/input/eventN` 에 직접 쓰려면 root 가 필요한데 이 기기는 `Permission denied` 다.
 * (⚠️ Kotlin 은 블록 주석이 **중첩**된다 — KDoc 안에 슬래시+별 이 들어가면 주석이 새로 열리고,
 *  에러는 «Unclosed comment» 로 **파일 끝**을 가리켜 원인이 안 보인다.)
 * 그래서 두·세 손가락은 **손으로 하거나, 여기처럼 Compose 가 진짜 멀티포인터 이벤트를 흘려서**
 * 검증하는 수밖에 없다. 손 테스트는 재현이 안 되므로 회귀를 못 막는다.
 *
 *     android/dev.sh test
 */
@RunWith(AndroidJUnit4::class)
class GestureTest {

    @get:Rule val rule = createComposeRule()

    /** 제스처가 내보낸 것을 전부 받아 적는다. */
    private class Rec : GestureSink {
        val zooms = mutableListOf<Triple<Float, Float, Float>>()
        val scrolls = mutableListOf<Pair<Int, Int>>()
        val moves = mutableListOf<Pair<Int, Int>>()
        val macs = mutableListOf<Int>()
        val modes = mutableListOf<GestureMode>()
        var placed: Offset? = null
        var taps = 0

        val zoomProduct get() = zooms.fold(1f) { acc, t -> acc * t.first }

        override fun placeCursor(at: Offset) { placed = at }
        override fun scroll(dy: Int, dx: Int) { scrolls += dy to dx }
        override fun moveRel(dx: Int, dy: Int) { moves += dx to dy }
        override fun zoom(change: Float, fx: Float, fy: Float) { zooms += Triple(change, fx, fy) }
        override fun macGesture(macKeyCode: Int) { macs += macKeyCode }
        override fun tap(at: Offset) { taps++ }
        override fun mode(m: GestureMode) { modes += m }
    }

    private fun setPad(profile: GestureProfile, sink: GestureSink) {
        rule.setContent {
            Box(Modifier.size(320.dp).testTag(TAG).deckGestures(profile, sink))
        }
    }

    private fun pad() = rule.onNodeWithTag(TAG)

    // ───────────────────────── 두 손가락 ─────────────────────────

    @Test
    fun T01_트랙패드_두_손가락을_벌리면_줌으로_고정된다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)

        pad().performTouchInput {
            down(0, Offset(200f, 300f))
            down(1, Offset(300f, 300f))
            repeat(6) { i ->
                updatePointerTo(0, Offset(200f - i * 20f, 300f))
                updatePointerTo(1, Offset(300f + i * 20f, 300f))
                move()
            }
            up(0); up(1)
        }

        assertTrue("줌 이벤트가 없다", rec.zooms.isNotEmpty())
        assertTrue("벌렸는데 배율이 안 커졌다: ${rec.zoomProduct}", rec.zoomProduct > 1.5f)
        assertTrue("줌으로 고정돼야 하는데 스크롤이 샜다: ${rec.scrolls}", rec.scrolls.isEmpty())
        assertTrue("ZOOM 으로 래치되지 않았다: ${rec.modes}", rec.modes.contains(GestureMode.Zoom))
        assertTrue("두 손가락인데 커서가 움직였다: ${rec.moves}", rec.moves.isEmpty())
    }

    @Test
    fun T02_트랙패드_두_손가락을_같이_밀면_스크롤로_고정된다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)

        pad().performTouchInput {
            down(0, Offset(200f, 200f))
            down(1, Offset(300f, 200f))
            repeat(6) { i ->
                updatePointerTo(0, Offset(200f, 200f + i * 20f))
                updatePointerTo(1, Offset(300f, 200f + i * 20f))
                move()
            }
            up(0); up(1)
        }

        assertTrue("스크롤 이벤트가 없다", rec.scrolls.isNotEmpty())
        assertTrue("SCROLL 로 래치되지 않았다: ${rec.modes}", rec.modes.contains(GestureMode.Scroll))
        assertTrue("같이 밀었는데 줌이 샜다: ${rec.zoomProduct}", abs(rec.zoomProduct - 1f) < 0.05f)
        // 아래로 밀었으면 dy 합이 양수
        assertTrue("스크롤 방향이 뒤집혔다: ${rec.scrolls}", rec.scrolls.sumOf { it.first } > 0)
    }

    @Test
    fun T03_스트림_두_손가락은_래치_없이_늘_줌과_이동이다() {
        val rec = Rec()
        setPad(GestureProfile.stream(invertScroll = false, zoomLevel = 1f, tapEnabled = true), rec)

        pad().performTouchInput {
            down(0, Offset(200f, 300f))
            down(1, Offset(300f, 300f))
            repeat(6) { i ->
                updatePointerTo(0, Offset(200f - i * 20f, 300f))
                updatePointerTo(1, Offset(300f + i * 20f, 300f))
                move()
            }
            up(0); up(1)
        }

        assertTrue("스트림에서 줌이 안 들어왔다", rec.zooms.isNotEmpty())
        assertTrue("스트림은 두 손가락에서 스크롤을 보내면 안 된다: ${rec.scrolls}", rec.scrolls.isEmpty())
    }

    /**
     * 🚨 회귀 방지 — **핀치 도중 제스처가 죽던 버그**.
     *
     * 예전에는 `pointerInput(invertScroll, zoom)` 처럼 «변하는 값»을 key 로 줬다.
     * 확대할 때마다 zoom 이 바뀌고 → Compose 가 핸들러를 취소·재시작하고 →
     * 다시 시작한 `awaitFirstDown` 은 **이미 내려가 있는 손가락을 못 잡는다**(내려가는 순간만 잡는다).
     * 결과: 핀치가 한 칸 움직이고 멈춘다. 이 테스트는 그 배선(줌 결과를 profile 로 되먹임)을
     * 그대로 재현하므로, key 를 다시 변하는 값으로 바꾸면 여기서 깨진다.
     */
    @Test
    fun T04_핀치_도중_배율이_되먹임돼도_제스처가_살아_있다() {
        val rec = Rec()
        rule.setContent {
            var zoomLevel by remember { mutableFloatStateOf(1f) }
            val sink = object : GestureSink by rec {
                override fun zoom(change: Float, fx: Float, fy: Float) {
                    zoomLevel = (zoomLevel * change).coerceIn(1f, 6f)   // 실제 화면과 같은 되먹임
                    rec.zoom(change, fx, fy)
                }
            }
            Box(
                Modifier.size(320.dp).testTag(TAG).deckGestures(
                    GestureProfile.stream(invertScroll = false, zoomLevel = zoomLevel, tapEnabled = true),
                    sink
                )
            )
        }

        pad().performTouchInput {
            down(0, Offset(200f, 300f))
            down(1, Offset(300f, 300f))
            repeat(8) { i ->
                updatePointerTo(0, Offset(200f - i * 10f, 300f))
                updatePointerTo(1, Offset(300f + i * 10f, 300f))
                move()
            }
            up(0); up(1)
        }

        assertTrue(
            "줌 이벤트가 ${rec.zooms.size}번만 들어왔다 — 핀치 도중 제스처가 죽는다",
            rec.zooms.size >= 5
        )
    }

    @Test
    fun T05_두_손가락_중_하나를_떼면_그_제스처는_멈춘다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)

        pad().performTouchInput {
            down(0, Offset(200f, 300f))
            down(1, Offset(300f, 300f))
            repeat(4) { i ->
                updatePointerTo(0, Offset(200f - i * 20f, 300f))
                updatePointerTo(1, Offset(300f + i * 20f, 300f))
                move()
            }
            up(1)
            repeat(6) { i -> updatePointerTo(0, Offset(100f, 300f + i * 30f)); move() }
            up(0)
        }

        // 하나를 뗀 뒤의 이동은 커서/스크롤/줌 어디로도 새면 안 된다.
        assertTrue("하나를 뗀 뒤 커서가 움직였다: ${rec.moves}", rec.moves.isEmpty())
        assertTrue("하나를 뗀 뒤 스크롤이 샜다: ${rec.scrolls}", rec.scrolls.isEmpty())
    }

    // ───────────────────────── 세 손가락 ─────────────────────────

    @Test
    fun T06_세_손가락_왼쪽으로_쓸면_오른쪽_스페이스다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)

        pad().performTouchInput { threeFingerSwipe(dx = -1f, dy = 0f) }

        assertEquals("맥 제스처가 정확히 한 번 나가야 한다", 1, rec.macs.size)
        assertEquals(MacKey.RIGHT, rec.macs.first())
        assertTrue("세 손가락인데 줌이 샜다: ${rec.zooms}", rec.zooms.isEmpty())
        assertTrue("세 손가락인데 스크롤이 샜다: ${rec.scrolls}", rec.scrolls.isEmpty())
        assertTrue("세 손가락인데 커서가 움직였다: ${rec.moves}", rec.moves.isEmpty())
    }

    @Test
    fun T07_세_손가락_오른쪽으로_쓸면_왼쪽_스페이스다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)
        pad().performTouchInput { threeFingerSwipe(dx = 1f, dy = 0f) }
        assertEquals(listOf(MacKey.LEFT), rec.macs)
    }

    @Test
    fun T08_세_손가락_위로_쓸면_미션컨트롤이다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)
        pad().performTouchInput { threeFingerSwipe(dx = 0f, dy = -1f) }
        assertEquals(listOf(MacKey.UP), rec.macs)
    }

    @Test
    fun T09_세_손가락_아래로_쓸면_앱윈도우다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)
        pad().performTouchInput { threeFingerSwipe(dx = 0f, dy = 1f) }
        assertEquals(listOf(MacKey.DOWN), rec.macs)
    }

    @Test
    fun T10_스트림은_세_손가락_판정이_더_멀다() {
        // 면이 크므로 임계가 70px — 트랙패드(48px)보다 멀리 가야 발동한다.
        val short = Rec()
        setPad(GestureProfile.stream(false, 1f, true), short)
        pad().performTouchInput { threeFingerSwipe(dx = -1f, dy = 0f, distance = 55f) }
        assertTrue("55px 로는 스트림에서 발동하면 안 된다: ${short.macs}", short.macs.isEmpty())
    }

    // ───────────────────────── 한 손가락 ─────────────────────────

    @Test
    fun T11_트랙패드_한_손가락은_커서를_옮긴다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)

        pad().performTouchInput {
            down(0, Offset(100f, 100f))
            repeat(6) { i -> updatePointerTo(0, Offset(100f + i * 25f, 100f)); move() }
            up(0)
        }

        assertTrue("커서 이동이 없다", rec.moves.isNotEmpty())
        assertTrue("스크롤로 샜다: ${rec.scrolls}", rec.scrolls.isEmpty())
        // 감도 2배 — 오른쪽으로 갔으면 dx 합이 양수
        assertTrue("방향이 뒤집혔다: ${rec.moves}", rec.moves.sumOf { it.first } > 0)
    }

    @Test
    fun T12_스트림_한_손가락은_커서를_먼저_놓고_스크롤한다() {
        val rec = Rec()
        setPad(GestureProfile.stream(invertScroll = false, zoomLevel = 1f, tapEnabled = true), rec)

        pad().performTouchInput {
            down(0, Offset(150f, 150f))
            repeat(6) { i -> updatePointerTo(0, Offset(150f, 150f + i * 25f)); move() }
            up(0)
        }

        // 맥 스크롤은 포커스가 아니라 «커서 아래 창»으로 간다 — 먼저 커서를 옮겨야 한다.
        assertTrue("스크롤 전에 커서를 안 옮겼다", rec.placed != null)
        assertTrue("스크롤이 없다", rec.scrolls.isNotEmpty())
        assertTrue("커서 상대이동으로 샜다: ${rec.moves}", rec.moves.isEmpty())
    }

    private fun streamDragDownSum(invert: Boolean): Int {
        val rec = Rec()
        setPad(GestureProfile.stream(invertScroll = invert, zoomLevel = 1f, tapEnabled = true), rec)
        pad().performTouchInput {
            down(0, Offset(150f, 150f))
            repeat(6) { i -> updatePointerTo(0, Offset(150f, 150f + i * 25f)); move() }
            up(0)
        }
        return rec.scrolls.sumOf { it.first }
    }

    @Test
    fun T13_스트림_스크롤_정방향은_아래로_민_만큼_양수다() {
        assertTrue(streamDragDownSum(invert = false) > 0)
    }

    @Test
    fun T14_스트림_스크롤_반전은_부호를_뒤집는다() {
        assertTrue(streamDragDownSum(invert = true) < 0)
    }

    // ───────────────────────── 탭 ─────────────────────────

    /**
     * 🚨 회귀 방지 — **탭은 지연 없이 나가야 한다.**
     *
     * 전에는 `detectTapGestures(onDoubleTap = …)` 이라 더블탭 후보를 기다리느라
     * 모든 단일 탭이 대기시간(약 300ms)만큼 늦게 나갔다. `waitForIdle()` 직후에는 0 이었다.
     * 지금은 `waitForIdle()` 만으로 1 이어야 한다 — `waitUntil` 로 눙치면 이 회귀를 못 잡는다.
     */
    @Test
    fun T15_탭은_지연_없이_즉시_나간다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)
        pad().performTouchInput { click(Offset(160f, 160f)) }
        rule.waitForIdle()
        assertEquals("탭이 더블탭 대기 뒤로 밀렸다", 1, rec.taps)
    }

    /**
     * 빠른 두 번의 탭은 **클릭 두 번**으로 나가야 한다.
     * 맥의 더블클릭은 «빠른 두 번의 클릭»이고, `clickState` 2 를 세우는 건 deckd 쪽
     * `InputInjector`(0.35초 창)다. 폰이 두 번째 탭을 삼키면 맥은 더블클릭을 볼 수 없다.
     */
    @Test
    fun T15b_두_번_빠르게_탭하면_탭이_두_번_나간다() {
        val rec = Rec()
        setPad(GestureProfile.trackpad(sensitivity = 2f, zoomLevel = 1f), rec)
        pad().performTouchInput { doubleClick(Offset(160f, 160f)) }
        rule.waitForIdle()
        assertEquals("두 번째 탭이 삼켜졌다 — 맥에 더블클릭을 보낼 수 없다", 2, rec.taps)
    }

    @Test
    fun T16_tapEnabled_가_꺼지면_탭을_안_보낸다() {
        val rec = Rec()
        setPad(GestureProfile.stream(invertScroll = false, zoomLevel = 1f, tapEnabled = false), rec)
        pad().performTouchInput { click(Offset(160f, 160f)) }
        rule.waitForIdle()
        assertEquals(0, rec.taps)
    }

    private companion object { const val TAG = "pad" }
}

/** 세 손가락을 같은 방향으로 [distance] px 만큼 쓸어 넘긴다. */
private fun androidx.compose.ui.test.TouchInjectionScope.threeFingerSwipe(
    dx: Float,
    dy: Float,
    distance: Float = 120f,
    steps: Int = 6
) {
    val start = listOf(Offset(120f, 260f), Offset(200f, 260f), Offset(280f, 260f))
    start.forEachIndexed { id, p -> down(id, p) }
    repeat(steps) { i ->
        val k = distance * (i + 1) / steps
        start.forEachIndexed { id, p -> updatePointerTo(id, Offset(p.x + dx * k, p.y + dy * k)) }
        move()
    }
    start.indices.forEach { up(it) }
}
