package kr.joonlab.foldlab.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 접힘 커버(475×751dp) 전용 압축 키보드 검증.
 *
 * **왜 수치로 재나**: 이 키보드가 존재하는 이유가 «펼침용 14열을 커버에 그대로 쓰면 키가 30.5dp 가 된다»
 * 였다. 그러면 검증도 «키가 몇 dp 인가»여야 한다. 스크린샷만 보면 다음 사람이 열을 하나 더 끼워 넣어도
 * 그럴듯해 보인다.
 *
 * 실측 근거(2026-09-22, SM-F971N): 커버 디스플레이 1248×1972px @ density 2.625 → **475×751dp**.
 * 터치 최소치는 Material 48dp / Apple 44pt. 여기서는 **44dp** 를 하한으로 잡는다.
 *
 * 스크린샷도 같이 남긴다 — 기계 게이트를 통과해도 눈으로 봐야 아는 것이 있다.
 * `/sdcard/Android/data/kr.joonlab.foldlab/files/cover-keyboard*.png`
 */
@RunWith(AndroidJUnit4::class)
class CoverKeyboardTest {

    @get:Rule val rule = createComposeRule()

    /** 커버에서 이 키보드가 실제로 받는 크기 — 475dp 폭, 높이는 가중치 0.80 몫(약 260dp). */
    private val coverWidth = 475.dp
    private val keyboardHeight = 260.dp

    private fun show(numLayer: Boolean) {
        rule.setContent {
            Box(Modifier.width(coverWidth).height(keyboardHeight).testTag(TAG)) {
                CyberdeckKeyboardCompact(
                    korean = false, activeMods = 0, numLayer = numLayer,
                    onKey = {}, modifier = Modifier.width(coverWidth).height(keyboardHeight)
                )
            }
        }
    }

    /** 키캡 «박스»(= 터치 타깃)의 dp 크기. 글자 노드를 재면 글리프 크기(7dp)가 나온다. */
    private fun keyDp(label: String): Pair<Float, Float> {
        val n = rule.onNodeWithTag("key:" + label).fetchSemanticsNode()
        val d = rule.density.density
        return n.size.width / d to n.size.height / d
    }

    private fun shoot(name: String) {
        val bmp = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun C01_기본_레이어_글자키가_터치_최소치를_넘는다() {
        show(numLayer = false)
        val letters = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                             "A", "S", "D", "F", "G", "H", "J", "K", "L",
                             "Z", "X", "C", "V", "B", "N", "M")
        val worst = letters.map { it to keyDp(it) }.minByOrNull { it.second.first }!!
        assertTrue(
            "가장 좁은 글자키 ${worst.first} 가 ${"%.1f".format(worst.second.first)}dp 다 — 44dp 미만이면 오타가 는다",
            worst.second.first >= 44f
        )
        val shortest = letters.map { it to keyDp(it) }.minByOrNull { it.second.second }!!
        assertTrue(
            "가장 낮은 글자키 ${shortest.first} 가 ${"%.1f".format(shortest.second.second)}dp 다",
            shortest.second.second >= 40f
        )
        shoot("cover-keyboard-base")
    }

    @Test
    fun C02_숫자_레이어에도_열이_열개다() {
        show(numLayer = true)
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val worst = digits.map { it to keyDp(it) }.minByOrNull { it.second.first }!!
        assertTrue(
            "숫자키 ${worst.first} 가 ${"%.1f".format(worst.second.first)}dp 다",
            worst.second.first >= 44f
        )
        shoot("cover-keyboard-num")
    }

    @Test
    fun C03_방향키는_역T자를_유지한다() {
        show(numLayer = false)
        val d = rule.density.density
        fun box(label: String) = rule.onNodeWithTag("key:" + label).fetchSemanticsNode().boundsInRoot
        val up = box("↑"); val down = box("↓"); val left = box("←"); val right = box("→")
        // ↑ 는 ↓ 바로 위에 — 가로 중심이 거의 같아야 한다(어긋나면 손이 매번 헤맨다).
        assertTrue(
            "↑ 가 ↓ 위에 있지 않다: ↑cx=${up.center.x / d}dp, ↓cx=${down.center.x / d}dp",
            kotlin.math.abs(up.center.x - down.center.x) / d < 4f
        )
        assertTrue("↑ 가 ↓ 보다 아래에 있다", up.bottom <= down.top + 1f)
        assertTrue("← 가 ↓ 왼쪽에 없다", left.right <= down.left + 1f)
        assertTrue("→ 가 ↓ 오른쪽에 없다", right.left >= down.right - 1f)
        // 방향키도 터치 최소치를 지켜야 한다
        listOf("↑" to up, "↓" to down, "←" to left, "→" to right).forEach { (n, b) ->
            assertTrue("$n 가 ${b.width / d}dp 로 좁다", b.width / d >= 44f)
        }
    }

    private companion object { const val TAG = "coverkb" }
}
