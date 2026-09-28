package kr.joonlab.foldlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 가로(펼침)용 플로팅 입력 독.
 *
 * 가로로 돌리는 행위 자체가 «화면을 크게 보겠다»는 뜻이므로 **기본값은 안 가리는 것**이다.
 * 평소엔 모서리에 손잡이만 있고, 필요할 때만 펼친다. 전체 키보드는 거기서 한 번 더.
 */
enum class DockState { Collapsed, Panel, FullKeyboard }

@Composable
fun BoxScope.FloatingDock(
    state: DockState,
    onState: (DockState) -> Unit,
    korean: Boolean,
    activeMods: Int,
    sensitivity: Float,
    onKey: (KeyDef) -> Unit,
    onMove: (Int, Int) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onButton: (Int, Boolean) -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
    onMacGesture: (Int) -> Unit,
    zoomLevel: Float = 1f,
    onMic: (() -> Unit)? = null,
    micOn: Boolean = false,
    micNote: String = "",
    /**
     * 세로 텔레메트리 줄과 **같은 토글 목록**. 가로에는 그 줄이 없어서 토글이 통째로 빠져 있었다 —
     * `TAP` 이 꺼지면 가로에서는 되돌릴 방법이 아예 없었다(2026-09-23 실제로 겪었다).
     * 능력은 세 자세가 같고 **담는 그릇만** 다르다는 것이 이 앱의 배치 원칙이다.
     */
    chips: List<ChipSpec> = emptyList(),
    onChip: (String) -> Unit = {},
    serverName: String? = null,
    connected: Boolean = false,
    onServerTap: () -> Unit = {},
    /** 보고 있는 맥 화면의 짧은 이름. 화면이 하나뿐이면 null. */
    displayName: String? = null,
    onDisplayTap: () -> Unit = {}
) {
    // 독 위치는 사용자가 옮길 수 있다 — 가리는 자리가 사람마다 다르다.
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 🚨 끌어서 화면 밖으로 내보내면 되돌릴 방법이 없다(손잡이가 안 보이니 잡을 수가 없다).
    //    창 기준 «실제로 그려진 자리»를 재서 거기서 역산해 가둔다 — 앵커가 BottomEnd 든
    //    BottomCenter 든, safe area 가 얼마든, 세로/가로가 바뀌든 같은 식이 그대로 맞는다.
    // 🚨 접힘 커버 가로는 **475dp 밖에 안 높다**. 펼침 가로(704dp) 기준으로 짜 둔 비율을 그대로 쓰면
    //    FULL 키보드가 행당 23dp 로 짓눌려 글자가 잘린다(2026-09-22 실측).
    //    자세 플래그가 아니라 «지금 높이»로 판단한다 — 독은 자기가 접혔는지 알 필요가 없다.
    val shortScreen = LocalConfiguration.current.screenHeightDp < 560

    val win = LocalWindowInfo.current.containerSize
    var dockSize by remember { mutableStateOf(IntSize.Zero) }
    var dockPos by remember { mutableStateOf(Offset.Zero) }

    /** 적어도 이만큼은 화면 안에 남긴다(px). */
    val keepIn = 4f

    fun clampBy(dx: Float, dy: Float) {
        if (dockSize == IntSize.Zero || win.width == 0 || win.height == 0) {
            offsetX += dx; offsetY += dy; return
        }
        // 오프셋 0 일 때의 자리 = 지금 자리 − 지금 오프셋
        val baseX = dockPos.x - offsetX
        val baseY = dockPos.y - offsetY
        val loX = keepIn - baseX
        val hiX = win.width - dockSize.width - keepIn - baseX
        val loY = keepIn - baseY
        val hiY = win.height - dockSize.height - keepIn - baseY
        // 독이 창보다 크면 lo > hi 가 된다 — 그때는 앵커 자리(0)에 둔다.
        offsetX = if (loX <= hiX) (offsetX + dx).coerceIn(loX, hiX) else 0f
        offsetY = if (loY <= hiY) (offsetY + dy).coerceIn(loY, hiY) else 0f
    }

    /** 회전·자세 전환으로 창이 바뀌면 나가 있던 독을 도로 끌어들인다. */
    LaunchedEffect(win, dockSize) { clampBy(0f, 0f) }

    val measure = Modifier.onGloballyPositioned {
        dockSize = it.size
        dockPos = it.boundsInWindow().topLeft
    }

    when (state) {
        DockState.Collapsed -> {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .then(measure)
                    .testTag("dock:collapsed")
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xE60D1117))
                    .border(1.dp, DeckColors.trackballRim, RoundedCornerShape(22.dp))
                    .pointerInput(Unit) { detectTapGestures { onState(DockState.Panel) } }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("⌨ 입력", color = DeckColors.accent, fontSize = 13.sp,
                     fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        DockState.Panel, DockState.FullKeyboard -> {
            val full = state == DockState.FullKeyboard
            Column(
                Modifier
                    .align(if (full) Alignment.BottomCenter else Alignment.BottomEnd)
                    .padding(10.dp)
                    .offset { if (full) IntOffset.Zero else IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    // FULL 은 오프셋을 안 쓴다 — 재 두면 사용자가 옮겨 둔 자리가 지워진다.
                    .then(if (full) Modifier else measure)
                    .testTag("dock:panel")
                    .fillMaxWidth(if (full) 1f else 0.42f)
                    .fillMaxHeight(
                        when {
                            full && shortScreen -> 0.80f   // 커버 가로: 키보드에 줄 높이가 이만큼은 필요하다
                            full -> 0.62f
                            else -> 0.60f
                        }
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF00D1117))
                    .border(1.dp, DeckColors.trackballRim, RoundedCornerShape(14.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // 손잡이 — 끌면 독이 움직이고, 탭하면 접힌다
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f).height(22.dp)
                            .testTag("dock:handle")
                            .pointerInput(full) {
                                detectTapGestures { onState(DockState.Collapsed) }
                            }
                            .pointerInput(full) {
                                if (!full) detectDragGestures { _, d -> clampBy(d.x, d.y) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.width(46.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(DeckColors.trackballRim))
                    }
                    DockChip(if (full) "작게" else "FULL", full) {
                        onState(if (full) DockState.Panel else DockState.FullKeyboard)
                    }
                }

                // 맥 제스처 단축 + 서버 + 토글을 **한 줄**에 담고 가로로 굴린다.
                // 🚨 줄을 새로 만들지 않는다 — 커버 가로는 높이가 475dp 뿐이라 22dp 를 더 먹으면
                //    FULL 키보드가 다시 짓눌린다(3차에 행당 23dp 로 떨어져 글자가 잘렸다).
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("dock:actions"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 🚨 목록을 여기서 **따로 만들지 않는다.** 전에는 독이 제스처 칩
                    //    (SP< SP> MC 앱창)을 스스로 그렸는데, 세로와 같은 칩 목록을 받게 되자
                    //    **같은 칩이 두 번** 나왔다(2026-09-23 스크린샷으로 발견).
                    //    이 랩에서 이미 데인 형태다 — 같은 판단이 두 곳에 있으면 반드시 어긋난다.
                    //    그러니 그릴 것은 전부 `chips` 가 정하고, 독은 **그리기만** 한다.
                    if (serverName != null) ServerTag(serverName, connected, onServerTap)
                    if (displayName != null) DisplayTag(displayName, onDisplayTap)
                    if (serverName != null || displayName != null) {
                        Box(Modifier.width(1.dp).height(16.dp).background(DeckColors.trackballRim))
                    }
                    chips.forEach { c ->
                        DockChip(c.label, c.on,
                                 Modifier.widthIn(min = 44.dp).testTag("chip:" + c.id)) { onChip(c.id) }
                    }
                }

                if (full) {
                    CyberdeckKeyboard(korean, activeMods, onKey, Modifier.fillMaxWidth().weight(1f))
                    // 커버 가로에서는 트랙패드 86dp 를 키보드에 준다 — 둘 다 넣으면 둘 다 못 쓴다.
                    // (FULL 이 아닌 Panel 로 돌아가면 트랙패드가 전면에 나온다.)
                    if (!shortScreen) {
                        TrackpadStrip(sensitivity, onMove, onScroll, onClick, onButton,
                            Modifier.fillMaxWidth().height(86.dp), compact = true, zoomLevel = zoomLevel,
                            onZoom = onZoom, onMacGesture = onMacGesture)
                    }
                } else {
                    TrackpadStrip(sensitivity, onMove, onScroll, onClick, onButton,
                        Modifier.fillMaxWidth().weight(1f), zoomLevel = zoomLevel,
                        onZoom = onZoom, onMacGesture = onMacGesture,
                        onMic = onMic, micOn = micOn, micNote = micNote)
                    DeckKeyRow(KeyRows.essential, korean, activeMods, onKey,
                        Modifier.fillMaxWidth().height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun DockChip(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) DeckColors.accent else DeckColors.keycap)
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) DeckColors.bg else DeckColors.text,
             fontSize = 11.sp, fontFamily = FontFamily.Monospace,
             fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
