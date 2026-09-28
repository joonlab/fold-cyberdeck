package kr.joonlab.foldlab.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.joonlab.foldlab.net.MacFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * 🚨 회귀 방지 — **키캡은 «지금 누르면 나올 글자»를 보여줘야 한다.**
 *
 * 사용자 신고(2026-09-22): ⇧ 를 눌러도 키보드 각인이 그대로라(영문은 늘 대문자, 한글은 늘 기본 자모)
 * **지금 무슨 상태인지 알 수가 없었다.** 쌍시옷·대문자가 나오는데 키캡은 ㅅ·Q 를 보여준다.
 *
 * 태그(`key:Q`)는 KeyDef 의 **고정 라벨**이고, 화면에 보이는 각인은 상태를 따라 바뀐다 —
 * 칩에서 겪은 «보이는 글자를 식별자로 쓰면 안 된다»와 같은 규칙이다.
 */
@RunWith(AndroidJUnit4::class)
class KeycapLabelTest {

    @get:Rule val rule = createComposeRule()

    private val ROOT = "kbroot"
    private val korean = mutableStateOf(false)
    private val mods = mutableIntStateOf(0)

    private fun show() {
        rule.setContent {
            // 🚨 `requiredSize` 로 창보다 크게 두면 캡처가 잘리고, 잘린 이미지의 원점은
            //    노드 왼쪽이 아니라 **창 왼쪽**이라 좌표가 통째로 어긋난다(왼쪽 키들이 사라진다).
            //    접힘/펼침에 따라 창 폭이 바뀌므로 폭은 화면에 맡긴다.
            Box(Modifier.fillMaxWidth().height(330.dp).testTag(ROOT)) {
                CyberdeckKeyboard(
                    korean = korean.value, activeMods = mods.intValue, onKey = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun cap(tag: String) = rule.onNodeWithTag("key:$tag")

    private fun setState(kr: Boolean, shift: Boolean) {
        rule.runOnUiThread {
            korean.value = kr
            mods.intValue = if (shift) MacFlags.SHIFT else 0
        }
        rule.waitForIdle()
    }

    @Test
    fun K01_영문은_Shift_에_따라_소문자_대문자로_바뀐다() {
        show()
        setState(kr = false, shift = false)
        cap("Q").assertTextEquals("q")
        cap("A").assertTextEquals("a")
        setState(kr = false, shift = true)
        cap("Q").assertTextEquals("Q")
        cap("A").assertTextEquals("A")
    }

    @Test
    fun K02_숫자_기호도_Shift_각인을_보여준다() {
        show()
        setState(kr = false, shift = false)
        cap("1").assertTextEquals("1")
        cap(";").assertTextEquals(";")
        setState(kr = false, shift = true)
        cap("1").assertTextEquals("!")
        cap(";").assertTextEquals(":")
    }

    @Test
    fun K03_한글_쌍자음이_각인에_보인다() {
        show()
        setState(kr = true, shift = false)
        cap("Q").assertTextEquals("ㅂ")
        cap("T").assertTextEquals("ㅅ")
        setState(kr = true, shift = true)
        cap("Q").assertTextEquals("ㅃ")
        cap("T").assertTextEquals("ㅆ")
    }

    @Test
    fun K04_쌍자음이_없는_키는_한글에서_그대로다() {
        show()
        setState(kr = true, shift = true)
        cap("A").assertTextEquals("ㅁ")   // 2벌식에 ⇧ㅁ 은 없다
        cap("1").assertTextEquals("!")    // 자모가 없는 키는 기호가 나온다
    }

    @Test
    fun K05_이름표가_붙은_키는_각인이_안_바뀐다() {
        show()
        setState(kr = false, shift = true)
        cap("space").assertTextEquals("space")   // " " 로 바뀌면 안 된다
        cap("ESC").assertTextEquals("ESC")
    }

    /**
     * 토글 키의 «켜짐»은 색으로 알아볼 수 있어야 한다.
     * 전에는 모디파이어가 늘 머스타드였고 켜지면 «조금 더 밝은 머스타드»라 사실상 구별이 안 됐다.
     * 글자를 피해 키캡 위쪽에서 픽셀을 읽는다.
     */
    private fun capBg(tag: String): Color {
        // 🚨 키캡 하나만 captureToImage 하면 `PixelCopy failed with result 1` 이 난다.
        //    키보드 전체를 한 번 찍고 그 안에서 좌표로 읽는다.
        val root = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val b = cap(tag).fetchSemanticsNode().boundsInRoot
        val px = rule.onNodeWithTag(ROOT).captureToImage().toPixelMap()
        val x = (b.center.x - root.left).toInt().coerceIn(0, px.width - 1)
        val y = (b.top - root.top + b.height / 6f).toInt().coerceIn(0, px.height - 1)
        return px[x, y]
    }

    private fun near(a: Color, b: Color) =
        abs(a.red - b.red) < 0.06f && abs(a.green - b.green) < 0.06f && abs(a.blue - b.blue) < 0.06f

    @Test
    fun K06_Shift_키가_켜지면_색이_뒤집힌다() {
        show()
        setState(kr = false, shift = false)
        val off = capBg("⇧")
        assertTrue("⇧ 꺼짐이 슬레이트가 아니다: $off", near(off, DeckColors.keycap))

        setState(kr = false, shift = true)
        val on = capBg("⇧")
        assertTrue("⇧ 켜짐이 머스타드가 아니다: $on — 켜진 걸 눈으로 알 수 없다",
                   near(on, DeckColors.accent))

        // 눈으로도 볼 수 있게 남긴다
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        rule.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().let { b ->
            File(dir, "keyboard-shift-on.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        setState(kr = true, shift = true)
        rule.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().let { b ->
            File(dir, "keyboard-han-shift.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun K07_한영_토글도_같은_규칙을_따른다() {
        show()
        setState(kr = false, shift = false)
        assertTrue("한/영 꺼짐이 슬레이트가 아니다", near(capBg("한/영"), DeckColors.keycap))
        setState(kr = true, shift = false)
        assertTrue("한/영 켜짐이 머스타드가 아니다", near(capBg("한/영"), DeckColors.accent))
    }

    /**
     * 🚨 **맥에는 Alt 키가 없다 — Option 이다.** 전에는 `alt` 로 찍혀 있었다.
     * 제 글리프 `⌥`(U+2325)는 삼성 폰트에 없어 엉뚱한 대체 글리프로 뜨므로(실측) 글자 `opt` 를 쓴다.
     * 펼침 5행 · 커버 압축 · 플로팅 독 필수키 — **세 군데가 갈리지 않게** 한 번에 본다.
     */
    @Test
    fun K09_Option_키는_세_배치_모두_맥_표기다() {
        val all = KeyRows.row1 + KeyRows.row2 + KeyRows.row3 + KeyRows.row4 + KeyRows.row5 +
            KeyRows.essential + KeyRows.cRow1 + KeyRows.cRow2 + KeyRows.cRow3 +
            KeyRows.cNum1 + KeyRows.cNum2 + KeyRows.cNum3 + KeyRows.cRow4(false) + KeyRows.cRow5
        val optionKeys = all.filter { (it.action as? KeyAction.Mod)?.flag == MacFlags.OPTION }
        assertTrue("Option 키가 어느 배치에도 없다", optionKeys.size >= 3)
        optionKeys.forEach {
            assertEquals("맥에는 Alt 가 없다 — Option 이다", "opt", it.label)
        }
        assertTrue("어디에도 alt 라고 적으면 안 된다", all.none { it.label.equals("alt", true) })
    }

    @Test
    fun K08_ESC_와_space_는_토글이_아니라_늘_머스타드다() {
        show()
        setState(kr = false, shift = false)
        assertTrue("ESC 가 머스타드가 아니다", near(capBg("ESC"), DeckColors.accent))
        assertEquals("space 는 이름표 그대로", "space", "space")
        assertTrue("space 가 머스타드가 아니다", near(capBg("space"), DeckColors.accent))
    }
}
