package kr.joonlab.foldlab.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kr.joonlab.foldlab.net.DeckStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **세 자세가 «같은 능력»을 담는가.**
 *
 * 2026-09-23 사용자 지적: 「펼침 세로 위주로만 디자인됐다」. 실제로 그랬다 —
 * 토글 11개와 서버 전환이 **세로에만** 있었고, 가로(펼침·커버 둘 다)에는 통째로 없었다.
 * `TAP` 이 꺼졌던 일이 그 구멍의 실제 사례다. 가로였다면 되돌릴 방법이 아예 없었다.
 *
 * 원칙: **능력은 세 자세가 같고, 담는 그릇과 배치만 화면 비율에 맞춘다.**
 * 그래서 「어느 자세에서 무엇이 빠졌나」를 눈이 아니라 여기서 센다.
 *
 * ⚠️ `zone` 만 예외다 — 세로 배치(스트림/키보드/패드 비율)를 고르는 토글이라 가로에서는
 *    아무 효과가 없다. 죽은 컨트롤을 보여 주면 「눌렀는데 안 된다」가 된다.
 */
@RunWith(AndroidJUnit4::class)
class PostureParityTest {

    @get:Rule val rule = createComposeRule()

    /** 세로 텔레메트리 줄이 담는 토글 — MainActivity 의 목록과 같은 순서. */
    private val portraitChips = listOf(
        ChipSpec("SP<", "SP<"), ChipSpec("SP>", "SP>"), ChipSpec("MC", "MC"), ChipSpec("앱창", "앱창"),
        ChipSpec("zone", "KB+PAD", true),
        ChipSpec("abs", "ABS"), ChipSpec("han", "한/영"), ChipSpec("tap", "TAP", true),
        ChipSpec("cmd", "⌘"), ChipSpec("opt", "opt"), ChipSpec("zoom", "⤢1x"),
        ChipSpec("invert", "↕반전"), ChipSpec("sync", "SYNC"), ChipSpec("end", "END")
    )

    /** 가로에서 반드시 닿아야 하는 것 = 세로 목록에서 `zone` 만 뺀 것. */
    private val mustReachInLandscape = portraitChips.map { it.id } - "zone"

    /** 화면이 둘 이상일 때 태그에 뜨는 이름. null 이면 화면이 하나뿐인 상황. */
    private val twoDisplays: String? = "내장"

    private fun showPortraitBar(displayName: String? = twoDisplays) {
        rule.setContent {
            TelemetryBar(
                stats = DeckStats(connected = true),
                chips = portraitChips, onChip = {},
                modifier = Modifier.fillMaxWidth().height(22.dp),
                serverName = "노트북",
                displayName = displayName
            )
        }
    }

    /** [heightDp] 로 «지금 높이»를 속여 커버 가로(475dp)와 펼침 가로(704dp)를 둘 다 본다. */
    private fun showDock(heightDp: Int, widthDp: Int, full: Boolean, displayName: String? = twoDisplays) {
        rule.setContent {
            val base = LocalConfiguration.current
            val conf = Configuration(base).apply {
                screenHeightDp = heightDp
                screenWidthDp = widthDp
            }
            CompositionLocalProvider(LocalConfiguration provides conf) {
                var state by remember {
                    mutableStateOf(if (full) DockState.FullKeyboard else DockState.Panel)
                }
                Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag("posture")) {
                    FloatingDock(
                        state = state, onState = { state = it },
                        korean = false, activeMods = 0, sensitivity = 2f,
                        onKey = {}, onMove = { _, _ -> }, onScroll = { _, _ -> },
                        onClick = {}, onButton = { _, _ -> },
                        onZoom = { _, _, _ -> }, onMacGesture = {},
                        chips = portraitChips.filterNot { it.id == "zone" }, onChip = {},
                        serverName = "노트북", connected = true, onServerTap = {},
                        displayName = displayName
                    )
                }
            }
        }
    }

    /** 눈으로도 한 번 본다 — 치수가 맞아도 «읽히는가»는 그림으로만 갈린다. */
    private fun shoot(name: String) {
        val bmp = rule.onNodeWithTag("posture").captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ⚠️ `setContent` 는 테스트당 한 번만 부를 수 있다 — 자세마다 테스트를 나눈다.
    @Test
    fun P05_펼침_가로_독_모습을_남긴다() {
        showDock(heightDp = 704, widthDp = 933, full = false)
        shoot("posture-unfolded-landscape")
    }

    @Test
    fun P06_커버_가로_FULL_모습을_남긴다() {
        showDock(heightDp = 475, widthDp = 751, full = true)
        shoot("posture-cover-landscape-full")
    }

    @Test
    fun P01_세로에는_토글_전부와_서버가_있다() {
        showPortraitBar()
        portraitChips.forEach { rule.onNodeWithTag("chip:${it.id}").assertExists() }
        rule.onNodeWithTag("server").assertExists()
        rule.onNodeWithTag("display").assertExists()
    }

    @Test
    fun P02_펼침_가로_독에도_같은_것이_담긴다() {
        showDock(heightDp = 704, widthDp = 933, full = false)
        mustReachInLandscape.forEach {
            rule.onNodeWithTag("chip:$it").assertExists()
        }
        rule.onNodeWithTag("server").assertExists()
        rule.onNodeWithTag("display").assertExists()
    }

    @Test
    fun P03_커버_가로_독에도_같은_것이_담긴다() {
        // 475dp — 여기서 FULL 키보드가 한 번 짓눌린 적이 있다(3차). 그래서 커버도 따로 센다.
        showDock(heightDp = 475, widthDp = 751, full = false)
        mustReachInLandscape.forEach {
            rule.onNodeWithTag("chip:$it").assertExists()
        }
        rule.onNodeWithTag("server").assertExists()
        rule.onNodeWithTag("display").assertExists()
    }

    @Test
    fun P04_커버_가로_FULL_에서도_토글이_살아_있다() {
        // FULL 은 키보드에 높이를 다 내주는 상태다. 그래도 토글이 사라지면 안 된다 —
        // 액션 줄을 **제스처 줄에 합쳐** 높이를 더 안 먹게 한 이유가 이것이다.
        showDock(heightDp = 475, widthDp = 751, full = true)
        rule.onNodeWithTag("chip:tap").assertExists()
        rule.onNodeWithTag("server").assertExists()
        rule.onNodeWithTag("display").assertExists()
    }

    /**
     * 화면이 하나뿐이면 디스플레이 태그는 **없어야** 한다 — 눌러도 아무 일이 없는 컨트롤은
     * 「눌렀는데 안 된다」가 된다(zone 을 가로에서 뺀 것과 같은 원칙).
     */
    @Test
    fun P07_화면이_하나면_세로에_디스플레이_태그가_없다() {
        showPortraitBar(displayName = null)
        rule.onNodeWithTag("display").assertDoesNotExist()
    }

    @Test
    fun P08_화면이_하나면_독에도_디스플레이_태그가_없다() {
        showDock(heightDp = 704, widthDp = 933, full = false, displayName = null)
        rule.onNodeWithTag("display").assertDoesNotExist()
    }
}
