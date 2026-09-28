package kr.joonlab.foldlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 노트북식 트랙패드 — 넓은 가로 패드 + 그 아래 L/R 버튼.
 *
 * 왜 원형 트랙볼을 버렸나: 모서리에 박힌 작은 원은 면적이 좁아 세밀한 포인팅이 안 되고,
 * 4·5행 키를 침범해 키보드까지 좁혔다. 가로 스트립은 같은 높이로 **면적이 몇 배**고,
 * 「노트북 하단」이라는 익숙한 배치라 설명이 필요 없다.
 *
 * 제스처는 진짜 트랙패드와 같게 맞춘다:
 *   한 손가락 드래그 = 커서 이동 · 탭 = 좌클릭 · 더블탭 = 줌 리셋
 *   **두 손가락**: 벌리거나 오므리면 = 화면 확대/축소, 같이 밀면 = 스크롤
 *   **세 손가락**: ←/→ 스페이스 전환, ↑ 미션 컨트롤, ↓ 앱 윈도우
 *
 * 두 손가락이 스크롤과 줌 둘 다인 게 모순처럼 보이지만, macOS 트랙패드가 정확히 그렇게 한다.
 * **손가락 «간격»이 변하면 줌, 간격이 그대로면 스크롤** — 제스처 시작 때 한 번 판정하고 고정한다
 * (매 프레임 다시 판정하면 줌 중에 스크롤로 튀어 화면이 요동친다).
 */
@Composable
fun TrackpadStrip(
    sensitivity: Float,
    onMove: (Int, Int) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onButton: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 현재 확대 배율 — 확대 중에는 줌 계산이 배율을 알아야 한다. */
    zoomLevel: Float = 1f,
    /** 두 손가락 핀치 — (배율변화, 트랙패드 크기 대비 이동 비율) */
    onZoom: (Float, Float, Float) -> Unit = { _, _, _ -> },
    /** 세 손가락 — macOS 가상 키코드(↑↓←→) */
    onMacGesture: (Int) -> Unit = {},
    /** 트랙패드 오른쪽에 붙일 마이크 버튼. null 이면 안 그린다. */
    onMic: (() -> Unit)? = null,
    micOn: Boolean = false,
    /** 켜져 있을 때 아래에 적을 한 마디(`기기`/`망`). */
    micNote: String = ""
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TrackpadSurface(
                sensitivity = sensitivity,
                zoomLevel = zoomLevel,
                onMove = onMove,
                onScroll = onScroll,
                onClick = onClick,
                onZoom = onZoom,
                onMacGesture = onMacGesture,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            if (onMic != null) {
                MicButton(
                    on = micOn, note = micNote, onClick = onMic,
                    // 🚨 순서가 중요하다. `width(x).fillMaxHeight()` 로 쓰면 이 Compose 버전에서
                    //    측정이 깨져 **컴포지션이 통째로 사라진다**(테스트에 «No compose hierarchies
                    //    found» 로 나타난다 — 예외도 크래시 로그도 없어 원인을 찾기 어렵다).
                    //    `fillMaxHeight()` 를 먼저 건다.
                    modifier = Modifier.fillMaxHeight().width(if (compact) 56.dp else 68.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(if (compact) 32.dp else 40.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ClickButton("L", Modifier.weight(1f)) { onButton(0, it) }
            ClickButton("R", Modifier.weight(1f)) { onButton(1, it) }
        }
    }
}

@Composable
private fun TrackpadSurface(
    sensitivity: Float,
    zoomLevel: Float,
    onMove: (Int, Int) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
    onMacGesture: (Int) -> Unit,
    modifier: Modifier
) {
    var gmode by remember { mutableStateOf(GestureMode.Idle) }
    val active = gmode != GestureMode.Idle

    // 판정은 전부 ui/Gestures.kt 한 곳에 있다 — 스트림과 같은 코드를 쓴다.
    // 매 리컴포지션마다 새로 만든다: deckGestures 가 rememberUpdatedState 로 최신을 본다.
    val sink = object : GestureSink {
        override fun moveRel(dx: Int, dy: Int) = onMove(dx, dy)
        override fun scroll(dy: Int, dx: Int) = onScroll(dy, dx)
        override fun zoom(change: Float, fx: Float, fy: Float) = onZoom(change, fx, fy)
        override fun macGesture(macKeyCode: Int) = onMacGesture(macKeyCode)
        override fun tap(at: Offset) = onClick()
        override fun mode(m: GestureMode) { gmode = m }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DeckColors.trackball)
            .deckGestures(GestureProfile.trackpad(sensitivity, zoomLevel), sink),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 22f
            val c = (if (active) DeckColors.accent else DeckColors.trackballRim)
                .copy(alpha = if (active) 0.5f else 0.30f)
            var y = step
            while (y < size.height) {
                var x = step
                while (x < size.width) { drawCircle(c, radius = 1.4f, center = Offset(x, y)); x += step }
                y += step
            }
        }
        Text(
            when (gmode) {
                GestureMode.Scroll -> "SCROLL"
                GestureMode.Zoom -> "ZOOM"
                GestureMode.Mac -> "GESTURE"
                else -> "TRACKPAD"
            },
            color = (if (active) DeckColors.accent else DeckColors.textDim).copy(alpha = 0.55f),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 마이크 버튼 — 트랙패드 오른쪽에 붙는 세로 띠.
 *
 * 칩 줄에 섞어 두면 22dp 짜리 작은 타깃이고 다른 토글과 구별도 안 된다.
 * 받아쓰기는 «말하려고 손을 대는» 동작이라 손이 이미 가 있는 트랙패드 옆이 맞다.
 *
 * 🚨 아이콘은 **직접 그린다.** 이 폰트에는 `⌥`·`⇥` 가 없어 엉뚱한 글리프가 뜬 적이 있다 —
 * 이모지도 기기·폰트에 따라 달라진다. 선 몇 개면 되는 모양은 그리는 편이 확실하다.
 */
@Composable
private fun MicButton(on: Boolean, note: String, onClick: () -> Unit, modifier: Modifier) {
    val click = rememberUpdatedState(onClick)
    Box(
        modifier
            .testTag("mic")
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) DeckColors.accent else DeckColors.trackball)
            .pointerInput(Unit) { detectTapGestures { click.value() } },
        contentAlignment = Alignment.Center
    ) {
        val fg = if (on) DeckColors.bg else DeckColors.textDim
        Column(horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Canvas(Modifier.size(26.dp)) { drawMic(fg) }
            Text(
                if (on) note.ifEmpty { "듣는 중" } else "음성",
                color = fg, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1
            )
        }
    }
}

/** 마이크 픽토그램 — 캡슐 + U자 받침 + 스탠드. */
private fun DrawScope.drawMic(c: Color) {
    val w = size.width
    val h = size.height
    val capW = w * 0.40f
    drawRoundRect(
        color = c,
        topLeft = Offset((w - capW) / 2f, h * 0.05f),
        size = Size(capW, h * 0.50f),
        cornerRadius = CornerRadius(capW / 2f)
    )
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val pad = w * 0.16f
    drawArc(
        color = c, startAngle = 0f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(pad, h * 0.34f),
        size = Size(w - pad * 2f, h * 0.44f),
        style = stroke
    )
    drawLine(c, Offset(w / 2f, h * 0.78f), Offset(w / 2f, h * 0.93f),
             strokeWidth = w * 0.09f, cap = StrokeCap.Round)
}

@Composable
private fun ClickButton(label: String, modifier: Modifier, onDown: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) DeckColors.accentDown else DeckColors.mouseBtn)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; onDown(true)
                    try { tryAwaitRelease() } finally { pressed = false; onDown(false) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFF0D1117), fontSize = 13.sp,
             fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
