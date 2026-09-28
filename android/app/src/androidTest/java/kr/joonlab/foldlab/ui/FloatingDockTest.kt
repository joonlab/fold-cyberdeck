package kr.joonlab.foldlab.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 플로팅 독을 화면 밖으로 내보낼 수 없어야 한다.
 *
 * 왜 중요한가: 독을 끌어 화면 밖으로 내보내면 **되돌릴 방법이 없다** — 손잡이가 안 보이니 잡을 수가 없고,
 * 앱을 다시 켜야 한다. 그래서 «끌린다»가 아니라 «못 나간다»를 못 박는다.
 */
@RunWith(AndroidJUnit4::class)
class FloatingDockTest {

    @get:Rule val rule = createComposeRule()

    private var win: IntSize = IntSize.Zero

    private fun showPanel() {
        rule.setContent {
            win = LocalWindowInfo.current.containerSize
            var state by remember { mutableStateOf(DockState.Panel) }
            Box(Modifier.fillMaxSize()) {
                FloatingDock(
                    state = state, onState = { state = it },
                    korean = false, activeMods = 0, sensitivity = 2f,
                    onKey = {}, onMove = { _, _ -> }, onScroll = { _, _ -> },
                    onClick = {}, onButton = { _, _ -> },
                    onZoom = { _, _, _ -> }, onMacGesture = {}
                )
            }
        }
    }

    /** 손잡이를 잡고 [d] 만큼 끈다. 한 번에 확 던지면 슬롭 판정에 먹히므로 나눠서 민다. */
    private fun dragHandle(d: Offset) {
        rule.onNodeWithTag("dock:handle").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(d.x / 10f, d.y / 10f)) }
            up()
        }
        rule.waitForIdle()
    }

    private fun panelBounds() =
        rule.onNodeWithTag("dock:panel").fetchSemanticsNode().boundsInWindow

    @Test
    fun D01_왼쪽_위로_끝까지_끌어도_창_안에_남는다() {
        showPanel()
        dragHandle(Offset(-6000f, -6000f))
        val b = panelBounds()
        assertTrue("독이 창 왼쪽으로 나갔다: left=${b.left}", b.left >= -1f)
        assertTrue("독이 창 위로 나갔다: top=${b.top}", b.top >= -1f)
    }

    @Test
    fun D02_오른쪽_아래로_끝까지_끌어도_창_안에_남는다() {
        showPanel()
        dragHandle(Offset(6000f, 6000f))
        val b = panelBounds()
        assertTrue("독이 창 오른쪽으로 나갔다: right=${b.right}, win=${win.width}",
                   b.right <= win.width + 1f)
        assertTrue("독이 창 아래로 나갔다: bottom=${b.bottom}, win=${win.height}",
                   b.bottom <= win.height + 1f)
    }

    /**
     * 🚨 회귀 방지 — **접힘 커버 가로(751×475dp)에서 FULL 키보드가 짓눌리던 문제.**
     *
     * 독이 높이의 0.62 를 쓰고 그 안에서 트랙패드 86dp 까지 떼어 가면, 475dp 화면에서는
     * 5행 키보드 몫이 **행당 23dp** 로 떨어져 키캡 글자가 잘린다(2026-09-22 실측, 사용자 신고).
     * 자세가 아니라 «지금 높이»로 판단해야 한다 — 그래서 여기서도 화면 높이를 주입해 잰다.
     */
    private fun showFullAt(wDp: Int, hDp: Int) {
        rule.setContent {
            val base = LocalConfiguration.current
            val cfg = remember(base, wDp, hDp) {
                Configuration(base).apply {
                    screenWidthDp = wDp; screenHeightDp = hDp
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
            }
            CompositionLocalProvider(LocalConfiguration provides cfg) {
                Box(Modifier.requiredSize(wDp.dp, hDp.dp)) {
                    FloatingDock(
                        state = DockState.FullKeyboard, onState = {},
                        korean = false, activeMods = 0, sensitivity = 2f,
                        onKey = {}, onMove = { _, _ -> }, onScroll = { _, _ -> },
                        onClick = {}, onButton = { _, _ -> },
                        onZoom = { _, _, _ -> }, onMacGesture = {}
                    )
                }
            }
        }
    }

    private fun keyHeightDp(label: String): Float =
        rule.onNodeWithTag("key:" + label).fetchSemanticsNode().size.height / rule.density.density

    @Test
    fun D04_커버_가로_FULL_키보드가_짓눌리지_않는다() {
        showFullAt(751, 475)
        val h = keyHeightDp("Q")
        assertTrue("커버 가로 FULL 키 높이가 ${"%.1f".format(h)}dp 다 — 글자가 잘린다", h >= 40f)
    }

    @Test
    fun D05_펼침_가로_FULL_키보드도_그대로다() {
        showFullAt(932, 704)
        val h = keyHeightDp("Q")
        assertTrue("펼침 가로 FULL 키 높이가 ${"%.1f".format(h)}dp 다", h >= 40f)
    }

    @Test
    fun D03_적당히_끌면_그_자리에_따라온다() {
        showPanel()
        val before = panelBounds()
        dragHandle(Offset(-120f, -80f))
        val after = panelBounds()
        // 클램프가 «아무것도 못 움직이게» 만들어 버리면 그것도 버그다.
        assertTrue("독이 왼쪽으로 안 따라왔다: ${before.left} → ${after.left}", after.left < before.left - 40f)
        assertTrue("독이 위로 안 따라왔다: ${before.top} → ${after.top}", after.top < before.top - 20f)
    }
}
