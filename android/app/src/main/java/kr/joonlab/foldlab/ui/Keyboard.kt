package kr.joonlab.foldlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kr.joonlab.foldlab.input.Hangul
import kr.joonlab.foldlab.input.MacKey

/**
 * 참고한 원격 앱 영상의 5행 사이버덱 키보드.
 *
 * 행 구성은 고해상도 분석에서 읽어낸 그대로다:
 *   1행  ESC 1..0 - = ⌫
 *   2행  Q..P [ ] \
 *   3행  한/영 A..L ; ' ↵
 *   4행  ⇧ Z..M , . ↑          (우측은 트랙볼이 차지한다)
 *   5행  [좌/우클릭] ^ ⌥ ⌘ Space ← ↓ →
 */
/** 키 사이 간격. 좁을수록 키캡이 커지고 오타가 준다 — 폴드 화면에선 여백이 사치다. */
private val GAP = 2.dp

object KeyRows {
    // 🚨 Option 키는 **`alt` 가 아니다** — 맥에는 Alt 키가 없다.
    //    제 글리프 `⌥`(U+2325)는 삼성 폰트에 없어 엉뚱한 대체 글리프로 뜬다(실측 확인).
    //    그래서 맥 표기의 글자 `opt` 를 쓴다. (`⌘`·`⇧`·`⌫`·`↵` 는 폰트에 있어 글리프 그대로 쓴다.)
    private fun ch(c: Char, w: Float = 1f) = KeyDef(c.uppercaseChar().toString(), w, KeyAction.Ch(c))

    val row1: List<KeyDef> = listOf(
        // ESC·⌫ 폭은 백틱을 넣으면서 **빌려 줬다**(1.2→1.0, 1.4→1.25).
        // 안 그러면 1행 글자키가 46.4dp → 43.0dp 로 내려가 터치 최소치(44dp)를 깬다.
        // FullKeyboardWidthTest.W01 이 그 경계를 지킨다 — 키를 더 넣으려면 레이어로 빼라.
        KeyDef("ESC", 1.0f, KeyAction.Vk(MacKey.ESCAPE), accent = true),
        // ` 와 / 는 맥 실제 배치 그대로의 자리다. 둘이 없으면 `~`·`?` 를 낼 방법이 아예 없다
        // (⇧ 조합표에는 이미 있는데 **키가 없었다** — 2026-09-23 사용자 신고).
        ch('`'),
        ch('1'), ch('2'), ch('3'), ch('4'), ch('5'), ch('6'), ch('7'), ch('8'), ch('9'), ch('0'),
        ch('-'), ch('='),
        KeyDef("⌫", 1.25f, KeyAction.Vk(MacKey.DELETE))
    )
    val row2: List<KeyDef> = listOf(
        ch('q'), ch('w'), ch('e'), ch('r'), ch('t'), ch('y'), ch('u'), ch('i'), ch('o'), ch('p'),
        ch('['), ch(']'), ch('\\')
    )
    val row3: List<KeyDef> = listOf(
        KeyDef("한/영", 1.3f, KeyAction.HanYeong, accent = true),
        ch('a'), ch('s'), ch('d'), ch('f'), ch('g'), ch('h'), ch('j'), ch('k'), ch('l'),
        ch(';'), ch('\''),
        KeyDef("↵", 1.4f, KeyAction.Vk(MacKey.RETURN))
    )
    /** 4행 — 방향키는 뺀다. ↑ 만 여기 떨어져 있으면 역T자가 깨져 손이 헤맨다. */
    val row4: List<KeyDef> = listOf(
        KeyDef("⇧", 1.5f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.SHIFT)),
        ch('z'), ch('x'), ch('c'), ch('v'), ch('b'), ch('n'), ch('m'), ch(','), ch('.'), ch('/')
    )
    /** 4·5행 좌측 합계 폭 — 방향키 클러스터와 나란히 놓기 위해 둘을 맞춘다.
     *  ⚠️ row4 와 row5 의 가중치 합이 **정확히 이 값**이어야 한다. `/` 를 더하면서 10.5 → 11.5 로
     *  올렸고, 맞추려고 row5 의 space 를 6.5 → 7.5 로 넓혔다. 한쪽만 고치면 역T자가 어긋난다. */
    const val LEFT_W = 11.5f
    /** 플로팅 독에 넣을 최소 구성 — 화면을 덜 가리면서 실제로 자주 쓰는 것만. */
    val essential: List<KeyDef> = listOf(
        KeyDef("ESC", 1.3f, KeyAction.Vk(MacKey.ESCAPE), accent = true),
        KeyDef("tab", 1.1f, KeyAction.Vk(MacKey.TAB)),
        KeyDef("^", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.CONTROL), accent = true),
        KeyDef("opt", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.OPTION), accent = true),
        KeyDef("⌘", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.COMMAND), accent = true),
        KeyDef("한/영", 1.4f, KeyAction.HanYeong, accent = true),
        KeyDef("↵", 1.4f, KeyAction.Vk(MacKey.RETURN)),
        KeyDef("⌫", 1.2f, KeyAction.Vk(MacKey.DELETE)),
        KeyDef("←", 1f, KeyAction.Vk(MacKey.LEFT)),
        KeyDef("↑", 1f, KeyAction.Vk(MacKey.UP)),
        KeyDef("↓", 1f, KeyAction.Vk(MacKey.DOWN)),
        KeyDef("→", 1f, KeyAction.Vk(MacKey.RIGHT))
    )

    val row5: List<KeyDef> = listOf(
        KeyDef("^", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.CONTROL), accent = true),
        KeyDef("opt", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.OPTION), accent = true),
        KeyDef("⌘", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.COMMAND), accent = true),
        KeyDef("space", 7.5f, KeyAction.Ch(' '), accent = true),
        KeyDef("tab", 1f, KeyAction.Vk(MacKey.TAB))
    )
    // ── 접힘 커버(475x751dp) 전용 ───────────────────────────────────────────
    //
    // 왜 따로 두나: 커버 폭 475dp 에 펼침용 14열을 그대로 넣으면 키 하나가 **30.5dp(약 4.8mm)**
    // 가 된다 — 터치 최소치(44~48dp)의 2/3 다. 그래서 **10열**로 압축한다(45.3dp).
    // 숫자·기호는 한 열도 못 내주므로 `123` 레이어로 접었다.
    // 4·5행은 펼침과 같은 구조(왼쪽 덩어리 + 오른쪽 역T자)를 유지한다 —
    // 자세가 바뀔 때마다 방향키 위치가 달라지면 손이 매번 헤맨다.

    /** 커버 기본 레이어 1~3행. 각 10열. */
    val cRow1: List<KeyDef> = listOf(
        ch('q'), ch('w'), ch('e'), ch('r'), ch('t'), ch('y'), ch('u'), ch('i'), ch('o'), ch('p')
    )
    val cRow2: List<KeyDef> = listOf(
        ch('a'), ch('s'), ch('d'), ch('f'), ch('g'), ch('h'), ch('j'), ch('k'), ch('l'),
        KeyDef("⌫", 1f, KeyAction.Vk(MacKey.DELETE))
    )
    val cRow3: List<KeyDef> = listOf(
        KeyDef("⇧", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.SHIFT)),
        ch('z'), ch('x'), ch('c'), ch('v'), ch('b'), ch('n'), ch('m'), ch(','), ch('.')
    )

    /** `123` 레이어 1~3행. 펼침 키보드에서 숫자행·기호가 하던 몫을 여기로 옮겼다. */
    val cNum1: List<KeyDef> = listOf(
        ch('1'), ch('2'), ch('3'), ch('4'), ch('5'), ch('6'), ch('7'), ch('8'), ch('9'), ch('0')
    )
    val cNum2: List<KeyDef> = listOf(
        ch('-'), ch('='), ch('['), ch(']'), ch('\\'), ch(';'), ch('\''), ch('`'), ch('/'),
        KeyDef("⌫", 1f, KeyAction.Vk(MacKey.DELETE))
    )
    /** ⇧ 조합으로도 되지만, 좁은 화면에서는 한 번에 닿는 편이 낫다. */
    val cNum3: List<KeyDef> = listOf(
        KeyDef("⇧", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.SHIFT)),
        ch('~'), ch('!'), ch('?'), ch(':'), ch('"'), ch('<'), ch('>'), ch('('), ch(')')
    )

    /** 커버 4행 왼쪽 덩어리. `123` 키는 상태에 따라 라벨·강조가 바뀌므로 함수로 만든다. */
    fun cRow4(numLayer: Boolean): List<KeyDef> = listOf(
        KeyDef(if (numLayer) "ABC" else "123", 1.1f, KeyAction.NumLayer, accent = numLayer),
        KeyDef("한/영", 1.4f, KeyAction.HanYeong, accent = true),
        KeyDef("^", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.CONTROL), accent = true),
        KeyDef("opt", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.OPTION), accent = true),
        KeyDef("⌘", 1f, KeyAction.Mod(kr.joonlab.foldlab.net.MacFlags.COMMAND), accent = true)
    )
    val cRow5: List<KeyDef> = listOf(
        KeyDef("ESC", 1.2f, KeyAction.Vk(MacKey.ESCAPE), accent = true),
        KeyDef("tab", 1.1f, KeyAction.Vk(MacKey.TAB)),
        KeyDef("space", 2.2f, KeyAction.Ch(' '), accent = true),
        KeyDef("↵", 1.3f, KeyAction.Vk(MacKey.RETURN))
    )
    /** 커버 4·5행 좌측 합계 폭 — 오른쪽 역T자 클러스터와 나란히 놓기 위해 맞춘다. */
    const val C_LEFT_W = 5.8f

    val arrowUp = KeyDef("↑", 1f, KeyAction.Vk(MacKey.UP))
    val arrowLeft = KeyDef("←", 1f, KeyAction.Vk(MacKey.LEFT))
    val arrowDown = KeyDef("↓", 1f, KeyAction.Vk(MacKey.DOWN))
    val arrowRight = KeyDef("→", 1f, KeyAction.Vk(MacKey.RIGHT))
    /**
     * 역T자 위 두 칸. 아래 `←`·`→` 와 **의미가 짝이 맞게** 둔다 — 왼쪽이 처음, 오른쪽이 끝.
     * 그래야 위치를 따로 외울 필요가 없다.
     *
     * ⚠️ 맥에서 Home/End 는 «줄»이 아니라 **문서** 처음/끝이다. 줄 처음/끝은 `⌘←`·`⌘→` 로
     *    이미 된다(⌘ 는 스티키 모디파이어로 있다). 둘은 다른 동작이니 헷갈리지 말 것.
     */
    val keyHome = KeyDef("Home", 1f, KeyAction.Vk(MacKey.HOME))
    val keyEnd = KeyDef("End", 1f, KeyAction.Vk(MacKey.END))
}

@Composable
fun CyberdeckKeyboard(
    korean: Boolean,
    activeMods: Int,
    onKey: (KeyDef) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(DeckColors.bg)
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(GAP)
    ) {
        DeckKeyRow(KeyRows.row1, korean, activeMods, onKey, Modifier.weight(1f))
        DeckKeyRow(KeyRows.row2, korean, activeMods, onKey, Modifier.weight(1f))
        DeckKeyRow(KeyRows.row3, korean, activeMods, onKey, Modifier.weight(1f))
        // 4·5행은 한 덩어리다 — 오른쪽에 방향키를 **역T자**로 세우기 위해서.
        // ↑ 를 4행 끝에 두면 ↓ 위가 아니라 ← 위에 앉아 손이 매번 헤맨다.
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            Column(
                Modifier.weight(KeyRows.LEFT_W).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(GAP)
            ) {
                DeckKeyRow(KeyRows.row4, korean, activeMods, onKey, Modifier.weight(1f))
                DeckKeyRow(KeyRows.row5, korean, activeMods, onKey, Modifier.weight(1f))
            }
            Column(
                Modifier.weight(3f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(GAP)
            ) {
                // 위 두 칸은 비어 있었다. 터치스크린에는 촉감이 없어 «역T자 빈칸»이 주는 것은
                // 촉각이 아니라 시각뿐이고, 채워도 **↑ 의 자리는 안 움직인다**.
                // 그래서 지금까지 아예 낼 수 없던 Home·End 를 여기 둔다.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Keycap(KeyRows.keyHome, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.keyHome) }
                    Keycap(KeyRows.arrowUp, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowUp) }
                    Keycap(KeyRows.keyEnd, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.keyEnd) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Keycap(KeyRows.arrowLeft, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowLeft) }
                    Keycap(KeyRows.arrowDown, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowDown) }
                    Keycap(KeyRows.arrowRight, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowRight) }
                }
            }
        }
    }
}

/**
 * 접힘 커버용 압축 키보드 — 10열 3행 + (4·5행 덩어리 | 역T자 방향키).
 *
 * 기본은 트랙패드 우선이고(이 키보드는 칩으로 펴는 것), 펼치면 스트림이 그만큼 줄어든다.
 */
@Composable
fun CyberdeckKeyboardCompact(
    korean: Boolean,
    activeMods: Int,
    numLayer: Boolean,
    onKey: (KeyDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val r1 = if (numLayer) KeyRows.cNum1 else KeyRows.cRow1
    val r2 = if (numLayer) KeyRows.cNum2 else KeyRows.cRow2
    val r3 = if (numLayer) KeyRows.cNum3 else KeyRows.cRow3
    Column(
        modifier.background(DeckColors.bg).padding(horizontal = 2.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(GAP)
    ) {
        DeckKeyRow(r1, korean, activeMods, onKey, Modifier.weight(1f))
        DeckKeyRow(r2, korean, activeMods, onKey, Modifier.weight(1f))
        DeckKeyRow(r3, korean, activeMods, onKey, Modifier.weight(1f))
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            Column(
                Modifier.weight(KeyRows.C_LEFT_W).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(GAP)
            ) {
                DeckKeyRow(KeyRows.cRow4(numLayer), korean, activeMods, onKey, Modifier.weight(1f))
                DeckKeyRow(KeyRows.cRow5, korean, activeMods, onKey, Modifier.weight(1f))
            }
            // 펼침과 같은 역T자 — 자세가 바뀌어도 방향키 자리는 그대로여야 한다.
            // Home·End 도 같이 둔다. 능력은 세 자세가 같아야 한다는 원칙이다.
            Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Keycap(KeyRows.keyHome, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.keyHome) }
                    Keycap(KeyRows.arrowUp, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowUp) }
                    Keycap(KeyRows.keyEnd, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.keyEnd) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Keycap(KeyRows.arrowLeft, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowLeft) }
                    Keycap(KeyRows.arrowDown, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowDown) }
                    Keycap(KeyRows.arrowRight, korean, activeMods, Modifier.weight(1f)) { onKey(KeyRows.arrowRight) }
                }
            }
        }
    }
}

/** 임의의 키 목록을 한 행으로 그린다. 플로팅 독의 필수키 행도 이걸 쓴다. */
@Composable
fun DeckKeyRow(
    keys: List<KeyDef>,
    korean: Boolean,
    activeMods: Int,
    onKey: (KeyDef) -> Unit,
    modifier: Modifier
) {
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        keys.forEach { k -> Keycap(k, korean, activeMods, Modifier.weight(k.weight)) { onKey(k) } }
    }
}

/**
 * 키캡 하나.
 *
 * 🚨 **키캡은 «지금 누르면 나올 글자»를 보여줘야 한다.** 전에는 늘 대문자(영문)·기본 자모(한글)였고
 * ⇧ 를 눌러도 화면 어디에도 표시가 없어서, 지금 상태를 알 방법이 아예 없었다(2026-09-22 사용자 신고).
 * 이제 ⇧ 에 따라 `q ↔ Q` · `1 ↔ !` · `ㅂ ↔ ㅃ` 로 각인이 바뀐다.
 *
 * 🚨 **토글 키는 «꺼짐 = 슬레이트 / 켜짐 = 머스타드»로 색을 뒤집는다.** 전에는 모디파이어가
 * 늘 머스타드였고 켜지면 «조금 더 밝은 머스타드»라 사실상 구별되지 않았다.
 * ESC·space 처럼 토글이 아닌 강조 키는 그대로 머스타드다.
 */
@Composable
fun Keycap(
    key: KeyDef,
    korean: Boolean,
    activeMods: Int,
    modifier: Modifier,
    onTap: () -> Unit
) {
    var down by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 모디파이어·한영 토글은 반복하면 안 된다(누르고 있는 동안 계속 뒤집힌다).
    val repeatable = key.action is KeyAction.Ch || key.action is KeyAction.Vk
    val shift = (activeMods and kr.joonlab.foldlab.net.MacFlags.SHIFT) != 0

    val sticky = (key.action as? KeyAction.Mod)?.let { (activeMods and it.flag) != 0 } ?: false
    val isToggle = key.action is KeyAction.Mod || key.action is KeyAction.HanYeong ||
                   key.action is KeyAction.NumLayer
    // NumLayer 는 켜짐을 accent 로 실어 보낸다(Keycap 이 레이어 상태를 따로 알 필요가 없게).
    val on = sticky ||
        (korean && key.action is KeyAction.HanYeong) ||
        (key.action is KeyAction.NumLayer && key.accent)

    /** 머스타드 바탕인가 — 글자색을 어둡게 할지 정한다. */
    val mustard = if (isToggle) on else key.accent
    val bg = when {
        mustard -> if (down) DeckColors.accentDown else DeckColors.accent
        else -> if (down) DeckColors.keycapDown else DeckColors.keycap
    }
    val fg = if (mustard) DeckColors.bg else DeckColors.text

    // 라벨이 한 글자인 키만 바꾼다 — `space` 같은 이름표를 " " 로 만들면 안 된다.
    val label = when (val a = key.action) {
        is KeyAction.Ch -> if (key.label.length != 1) key.label else {
            val jamo = if (korean) Hangul.jamoFor(if (shift) a.ch.uppercaseChar() else a.ch) else null
            (jamo ?: (if (shift) MacKey.shiftChar(a.ch) else a.ch)).toString()
        }
        else -> key.label
    }

    Box(
        modifier
            .fillMaxHeight()
            // 계측 테스트가 «터치 타깃» 크기를 재려면 글자가 아니라 이 박스를 잡아야 한다
            // (글자 노드를 재면 7dp 가 나온다). 태그는 **변하지 않는** KeyDef.label 로 만든다 —
            // 화면에 보이는 각인은 ⇧·한영에 따라 바뀌므로 식별자로 쓰면 안 된다.
            .semantics(mergeDescendants = true) { testTag = "key:" + key.label }
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .pointerInput(key, repeatable) {
                detectTapGestures(
                    onPress = {
                        down = true
                        onTap()
                        // 길게 누르면 연타 — ⌫·방향키가 한 번씩만 먹으면 못 쓴다.
                        val repeater = if (repeatable) scope.launch {
                            delay(420)
                            while (true) { onTap(); delay(55) }
                        } else null
                        tryAwaitRelease()
                        repeater?.cancel()
                        down = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontSize = when {
                label.length >= 4 -> 8.sp
                label.length == 3 -> 9.5.sp
                label.length == 2 -> 11.sp
                else -> 12.sp
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun MouseKey(label: String, modifier: Modifier, onDown: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) DeckColors.accentDown else DeckColors.mouseBtn)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; onDown(true)
                    // finally 로 감싼다 — 리컴포지션 등으로 코루틴이 취소돼도 «떼기»는 나가야 한다.
                    // 안 그러면 버튼이 눌린 채 남아 이후 조작이 전부 이상해진다.
                    try { tryAwaitRelease() } finally { pressed = false; onDown(false) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = DeckColors.text, fontSize = 11.sp,
             fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
