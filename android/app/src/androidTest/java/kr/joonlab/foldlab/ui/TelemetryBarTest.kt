package kr.joonlab.foldlab.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.joonlab.foldlab.InputZone
import kr.joonlab.foldlab.net.DeckStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🚨 회귀 방지 — **라벨이 바뀌는 칩을 연속으로 누를 수 있어야 한다.**
 *
 * 사용자 신고(2026-09-22): 접힘 세로에서 `KB+PAD → PAD → 화면만` 칩이 **한 번만 먹고**,
 * 가로로 돌렸다 세로로 돌아와야 다시 한 번 먹었다.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryBarTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun B01_zone_칩을_연속으로_눌러도_계속_전환된다() {
        val zone = mutableStateOf(InputZone.Full)
        rule.setContent {
            val z = zone.value
            TelemetryBar(
                stats = DeckStats(),
                chips = listOf(ChipSpec("zone", z.label, z != InputZone.Off)),
                onChip = { id -> if (id == "zone") zone.value = zone.value.next() },
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
        }

        rule.onNodeWithTag("chip:zone").performClick()
        rule.waitForIdle()
        assertEquals("1번째 전환이 안 됐다", InputZone.PadOnly, zone.value)

        rule.onNodeWithTag("chip:zone").performClick()
        rule.waitForIdle()
        assertEquals("2번째 전환이 안 됐다 — 칩이 옛 이름을 붙들고 있다", InputZone.Off, zone.value)

        rule.onNodeWithTag("chip:zone").performClick()
        rule.waitForIdle()
        assertEquals("3번째 전환이 안 됐다", InputZone.Full, zone.value)
    }

    /**
     * 서버 표시는 **칩이 아니라 상태**다 — 연결 정보 옆에 두고, 탭하면 다음 맥으로 갈아탄다.
     *
     * 칩 줄에 뒀을 때는 다른 토글과 구별이 안 됐고(«눌러도 되는 것»으로 안 읽힌다),
     * 칩이 하나 늘어 END 가 밀려 잘렸다. 2026-09-23 사용자 지적으로 옮겼다.
     */
    @Test
    fun B02_서버_표시를_연속으로_눌러도_계속_순환한다() {
        val names = listOf("노트북", "홈맥")
        val idx = mutableStateOf(0)
        rule.setContent {
            TelemetryBar(
                stats = DeckStats(connected = true),
                chips = emptyList(),
                onChip = {},
                modifier = Modifier.fillMaxWidth().height(22.dp),
                serverName = names[idx.value],
                onServerTap = { idx.value = (idx.value + 1) % names.size }
            )
        }

        rule.onNodeWithText("노트북").assertExists()

        rule.onNodeWithTag("server").performClick()
        rule.waitForIdle()
        assertEquals("1번째 전환이 안 됐다", 1, idx.value)
        rule.onNodeWithText("홈맥").assertExists()

        // ★ 이름이 바뀐 뒤에도 같은 요소가 먹어야 한다.
        rule.onNodeWithTag("server").performClick()
        rule.waitForIdle()
        assertEquals("2번째 전환이 안 됐다 — 옛 람다를 붙들고 있다", 0, idx.value)
        rule.onNodeWithText("노트북").assertExists()
    }

    /** 칩 줄에는 더 이상 서버 항목이 없어야 한다 — 두 군데에 있으면 반드시 어긋난다. */
    @Test
    fun B03_서버는_칩_줄에_없다() {
        rule.setContent {
            TelemetryBar(
                stats = DeckStats(connected = true),
                chips = listOf(ChipSpec("sync", "SYNC"), ChipSpec("end", "END")),
                onChip = {},
                modifier = Modifier.fillMaxWidth().height(22.dp),
                serverName = "노트북"
            )
        }
        rule.onNodeWithTag("chip:server").assertDoesNotExist()
        rule.onNodeWithTag("server").assertExists()
        rule.onNodeWithTag("chip:end").assertExists()
    }
}
