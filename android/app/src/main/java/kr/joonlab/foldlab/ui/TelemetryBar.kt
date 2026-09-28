package kr.joonlab.foldlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import kr.joonlab.foldlab.net.DeckStats

/**
 * 스트림과 키보드 사이의 얇은 텔레메트리 + 토글 행.
 *
 * 참고한 원격 앱의 표기를 그대로 따른다:  `T3 0.10Mbps 14fps net 25ms 704x536 UDP (6)`
 * 오른쪽 버튼은 장식이 아니라 전부 실제 동작에 연결돼 있다.
 */
/**
 * 칩 하나.
 *
 * 🚨 **[id] 는 변하지 않는 이름, [label] 은 화면에 보이는 글자다. 겸하게 두면 안 된다.**
 * 전에는 칩 목록이 «라벨 → 켜짐» 맵이었고 눌렸을 때 라벨을 도로 보냈다. 그런데 zone 칩은
 * 라벨이 상태를 따라 바뀐다(`KB+PAD` → `PAD` → `화면만`) — 한 번 누르면 라벨이 달라져
 * 그 뒤로는 어느 분기에도 안 걸렸다(2026-09-22 사용자 신고).
 */
data class ChipSpec(val id: String, val label: String, val on: Boolean = false)

@Composable
fun TelemetryBar(
    stats: DeckStats,
    chips: List<ChipSpec>,
    onChip: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 지금 붙어 있는(붙으려는) 맥 이름. null 이면 표시하지 않는다. */
    serverName: String? = null,
    onServerTap: () -> Unit = {},
    /** 보고 있는 맥 화면의 짧은 이름. 화면이 하나뿐이면 null — 죽은 컨트롤은 안 보인다. */
    displayName: String? = null,
    onDisplayTap: () -> Unit = {}
) {
    Row(
        modifier
            .background(Color.Black)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 「어디에 붙었나」는 토글이 아니라 **상태**다. 그래서 칩 줄이 아니라 연결 정보 옆에 둔다.
        // 칩 줄에 있을 때는 다른 토글과 구별이 안 됐고, 칩이 하나 늘어 END 가 밀려 잘렸다.
        if (serverName != null) {
            ServerTag(serverName, stats.connected, onServerTap)
            Spacer(Modifier.width(3.dp))
        }
        // «어느 화면을 보나»도 서버와 같은 종류의 **상태**라 그 바로 옆에 둔다.
        if (displayName != null) {
            DisplayTag(displayName, onDisplayTap)
            Spacer(Modifier.width(6.dp))
        } else if (serverName != null) {
            Spacer(Modifier.width(3.dp))
        }
        val mbps = stats.bitrateBps / 1_000_000.0
        val netColor = when {
            !stats.connected -> DeckColors.bad
            stats.latencyMs > 120 -> DeckColors.warn
            else -> DeckColors.ok
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (stats.connected) "T1" else "--",
                color = netColor, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(5.dp))
            Text(
                buildString {
                    append(String.format("%.2fMbps", mbps))
                    append("  ${stats.fps}fps")
                    append("  net ${stats.latencyMs}ms")
                    append("  ${stats.streamW}x${stats.streamH}")
                    append("  UDP")
                    if (stats.lostFragments > 0) append(" (${stats.lostFragments})")
                },
                color = DeckColors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            chips.forEach { c -> ToggleChip(c) { onChip(c.id) } }
        }
    }
}

/**
 * 붙어 있는 맥 — 탭하면 다음 맥으로 갈아탄다.
 *
 * 전에는 붙어 있는 동안 대상을 바꿀 방법이 **아예 없었다**(전환 조작이 연결 오버레이 안에만
 * 있었고 그 오버레이는 끊겼을 때만 떴다). 칩 줄에 넣었더니 토글과 구별이 안 돼 여기로 옮겼다.
 *
 * ⚠️ 상태는 **글리프가 아니라 점**으로 그린다. `⌥` 가 삼성 폰트에 없어 알아볼 수 없는 획으로
 *    떴던 일이 있다(6차) — 상태 표시를 폰트에 맡기지 않는다.
 */
@Composable
internal fun ServerTag(name: String, connected: Boolean, onTap: () -> Unit) {
    val tap = rememberUpdatedState(onTap)
    Row(
        Modifier
            .testTag("server")
            .clip(RoundedCornerShape(3.dp))
            .background(DeckColors.keycap)
            .pointerInput(Unit) { detectTapGestures { tap.value() } }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (connected) DeckColors.ok else DeckColors.textDim)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            color = DeckColors.text, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1
        )
    }
}

/**
 * 보고 있는 맥 화면. 탭하면 **다음 화면**으로 넘어간다(서버 칩과 같은 순환).
 * 모양은 서버 태그와 같고 점 대신 작은 모니터를 그린다 — 글리프는 이 폰트에 없을 수 있어 도형으로.
 */
@Composable
internal fun DisplayTag(name: String, onTap: () -> Unit) {
    val tap = rememberUpdatedState(onTap)
    Row(
        Modifier
            .testTag("display")
            .clip(RoundedCornerShape(3.dp))
            .background(DeckColors.keycap)
            .pointerInput(Unit) { detectTapGestures { tap.value() } }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 8.dp, height = 6.dp)
                .border(1.dp, DeckColors.text, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            color = DeckColors.text, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1
        )
    }
}

@Composable
internal fun ToggleChip(chip: ChipSpec, onClick: () -> Unit) {
    // `pointerInput(Unit)` 은 핸들러를 한 번만 만든다 — **처음 붙은 람다를 영원히 붙든다.**
    // 지금은 그 람다가 붙잡는 게 고정 id 와 상태 델리게이트뿐이라 문제가 안 되지만,
    // 나중에 «값»을 붙잡는 핸들러가 들어오면 조용히 옛 값으로 헛돈다. 그래서 최신을 읽어 둔다.
    // (key 를 자주 바꾸는 쪽으로 풀면 제스처가 끊긴다 — ui/Gestures.kt 의 핀치 사고가 그것이다.)
    // ⚠️ 위 zone 칩 버그를 실제로 고친 것은 이게 아니라 **id 와 label 을 가른 것**이다.
    val click = rememberUpdatedState(onClick)
    Box(
        Modifier
            .testTag("chip:" + chip.id)
            .clip(RoundedCornerShape(3.dp))
            .background(if (chip.on) DeckColors.accent else DeckColors.keycap)
            .pointerInput(Unit) { detectTapGestures { click.value() } }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            chip.label,
            color = if (chip.on) DeckColors.bg else DeckColors.textDim,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
