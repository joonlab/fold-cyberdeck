package kr.joonlab.foldlab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 펼침 키보드의 **키 폭**을 잰다.
 *
 * `` ` `` 와 `/` 를 더하면서 1행이 14.6 → **15.6 단위**가 됐다(키가 약 6% 좁아진다).
 * 「기호를 더 넣자」는 요구가 또 오면 여기서 먼저 걸리게 둔다 —
 * 스크린샷만 보면 열을 하나 더 끼워 넣어도 그럴듯해 보인다(커버 키보드에서 이미 겪었다).
 *
 * 실측 근거(SM-F971N 펼침 세로): 화면 **704dp** 폭. 터치 최소치는 Material 48dp / Apple 44pt →
 * 여기서는 **44dp** 를 하한으로 잡는다.
 */
@RunWith(AndroidJUnit4::class)
class FullKeyboardWidthTest {

    @get:Rule val rule = createComposeRule()

    private val unfoldedWidth = 704.dp
    private val keyboardHeight = 300.dp
    private val TAG = "fullkb"

    private fun show() {
        rule.setContent {
            // 🚨 `width` 가 아니라 `requiredWidth` — 폰이 **접혀 있으면** 커버 폭(≈475dp)이 부모 제약이 되어
            //    704dp 가 475dp 로 눌리고, 키가 29dp 로 재져 «깨진 게 없는데 빨강»이 된다(2026-09-24 실측).
            Box(Modifier.requiredWidth(unfoldedWidth).height(keyboardHeight).testTag(TAG)) {
                CyberdeckKeyboard(
                    korean = false, activeMods = 0, onKey = {},
                    modifier = Modifier.requiredWidth(unfoldedWidth).height(keyboardHeight)
                )
            }
        }
    }

    private fun keyDp(label: String): Float {
        val n = rule.onNodeWithTag("key:$label").fetchSemanticsNode()
        return n.size.width / rule.density.density
    }

    @Test
    fun W01_모든_글자키가_터치_최소치를_넘는다() {
        show()
        // 1행이 가장 조밀하다 — 숫자·백틱이 거기 있다.
        val keys = listOf("`", "1", "5", "0", "-", "=",
                          "Q", "P", "[", "]", "\\",
                          "A", "L", ";", "'",
                          "Z", "M", ",", ".", "/")
        val worst = keys.map { it to keyDp(it) }.minByOrNull { it.second }!!
        assertTrue(
            "가장 좁은 키 '${worst.first}' 가 ${"%.1f".format(worst.second)}dp 다 — " +
                    "44dp 미만이면 오타가 는다. 키를 더 넣으려면 레이어로 빼라",
            worst.second >= 44f
        )
    }

    @Test
    fun W02_새로_넣은_백틱과_슬래시가_실제로_있다() {
        show()
        rule.onNodeWithTag("key:`").assertExists()
        rule.onNodeWithTag("key:/").assertExists()
    }
}
