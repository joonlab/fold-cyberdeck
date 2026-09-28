package kr.joonlab.foldlab.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 마이크 버튼은 **트랙패드 오른쪽 띠**다.
 *
 * 칩 줄에 섞어 두면 22dp 짜리 작은 타깃이고 다른 토글과 구별이 안 된다.
 * 받아쓰기는 «말하려고 손을 대는» 동작이라, 손이 이미 가 있는 트랙패드 옆이 맞다.
 */
@RunWith(AndroidJUnit4::class)
class TrackpadMicTest {

    @get:Rule val rule = createComposeRule()

    private val on = mutableStateOf(false)
    private val taps = mutableStateOf(0)

    private fun show(compact: Boolean = false) {
        rule.setContent {
            Box(Modifier.fillMaxWidth().height(180.dp).testTag(ROOT)) {
                TrackpadStrip(
                    sensitivity = 2f,
                    onMove = { _, _ -> }, onScroll = { _, _ -> }, onClick = {},
                    onButton = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    compact = compact,
                    onMic = { taps.value++ },
                    micOn = on.value,
                    micNote = if (on.value) "기기" else ""
                )
            }
        }
    }

    @Test
    fun P01_마이크_버튼이_트랙패드_오른쪽에_있다() {
        show()
        val d = rule.density.density
        val root = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val mic = rule.onNodeWithTag("mic").fetchSemanticsNode().boundsInRoot
        // 오른쪽 끝에 붙어 있어야 한다
        assertTrue("마이크가 오른쪽 끝에 없다: mic.right=${mic.right} root.right=${root.right}",
                   root.right - mic.right < 8f)
        // 트랙패드 높이를 꽉 채워야 «세로 띠»다
        assertTrue("마이크가 세로로 안 찼다: ${mic.height / d}dp", mic.height / d > 80f)
        // 손가락으로 누를 만해야 한다
        assertTrue("마이크 폭이 ${mic.width / d}dp 로 좁다", mic.width / d >= 44f)
    }

    @Test
    fun P02_눌리고_켜지면_표시가_바뀐다() {
        show()
        rule.onNodeWithTag("mic").performClick()
        rule.waitForIdle()
        assertEquals("마이크를 눌렀는데 안 불렸다", 1, taps.value)

        rule.runOnUiThread { on.value = true }
        rule.waitForIdle()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        rule.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().let { b ->
            File(dir, "trackpad-mic-on.png").outputStream()
                .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.runOnUiThread { on.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().let { b ->
            File(dir, "trackpad-mic-off.png").outputStream()
                .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun P03_마이크를_안_주면_안_그린다() {
        rule.setContent {
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                TrackpadStrip(
                    sensitivity = 2f,
                    onMove = { _, _ -> }, onScroll = { _, _ -> }, onClick = {},
                    onButton = { _, _ -> },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        rule.onNodeWithTag("mic").assertDoesNotExist()
    }

    private companion object { const val ROOT = "padroot" }
}
