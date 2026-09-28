package kr.joonlab.foldlab

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kr.joonlab.foldlab.input.Dictation
import kr.joonlab.foldlab.input.DictationEngine
import kr.joonlab.foldlab.input.Hangul
import kr.joonlab.foldlab.input.MacKey
import kr.joonlab.foldlab.net.*
import kr.joonlab.foldlab.ui.*

data class DeckServer(val name: String, val host: String, val port: Int)

/**
 * `이름@호스트:포트,이름@호스트:포트` 형식을 읽는다. 포트를 생략하면 8790.
 * 비어 있거나 전부 잘못됐으면 자리표시자 하나를 돌려준다(앱은 뜨고, 접속만 실패한다).
 */
fun parseDeckServers(spec: String): List<DeckServer> {
    val list = spec.split(',').mapNotNull { raw ->
        val item = raw.trim()
        if (item.isEmpty()) return@mapNotNull null
        val at = item.indexOf('@')
        val name = if (at > 0) item.substring(0, at).trim() else "Mac"
        val addr = if (at >= 0) item.substring(at + 1).trim() else item
        val colon = addr.lastIndexOf(':')
        val host = if (colon > 0) addr.substring(0, colon) else addr
        val port = if (colon > 0) addr.substring(colon + 1).toIntOrNull() ?: 8790 else 8790
        if (host.isBlank()) null else DeckServer(name, host, port)
    }
    return list.ifEmpty { listOf(DeckServer("Mac", "my-mac.local", 8790)) }
}

/** 세로에서 아래쪽을 얼마나 쓸지. 칩 하나로 순환한다 — 상황마다 원하는 게 다르다. */
enum class InputZone { Full, PadOnly, Off;
    fun next() = when (this) { Full -> PadOnly; PadOnly -> Off; Off -> Full }
    val label get() = when (this) { Full -> "KB+PAD"; PadOnly -> "PAD"; Off -> "화면만" }
}

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: DeckClient
    /** 배경으로 가면 마이크를 놓는다 — 화면 밖에서는 보낼 수도 없는데 듣고만 있게 된다. */
    private var dictation: Dictation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = DeckColors.bg) {
                    CyberdeckScreen(scope, onClient = { client = it }, onDictation = { dictation = it })
                }
            }
        }
    }

    override fun onStop() { super.onStop(); dictation?.destroy(); if (::client.isInitialized) client.pause() }
    override fun onStart() { super.onStart(); if (::client.isInitialized) client.resume() }
    override fun onDestroy() {
        super.onDestroy()
        dictation?.destroy()
        if (::client.isInitialized) client.disconnect()
        scope.cancel()
    }
}

private const val PREFS = "deck"

@Composable
fun CyberdeckScreen(
    scope: CoroutineScope,
    onClient: (DeckClient) -> Unit,
    onDictation: (Dictation) -> Unit = {}
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // 서버 목록은 소스에 박지 않는다 — 빌드 때 `local.properties` 의 `deck.servers`
    // (또는 환경변수 DECK_SERVERS)에서 BuildConfig.DECK_SERVERS 로 주입된다. config.example 참고.
    val servers = remember { parseDeckServers(BuildConfig.DECK_SERVERS) }
    var serverIdx by remember { mutableIntStateOf(prefs.getInt("server", 0).coerceIn(0, servers.lastIndex)) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var showSetup by remember { mutableStateOf(token.isBlank()) }

    var stats by remember { mutableStateOf(DeckStats()) }
    /** END 로 사용자가 끊었다 — 「접속」·서버 전환·토큰 저장 전까지 자동 재접속하지 않는다. */
    var endedByUser by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val client = remember { DeckClient(scope, onStats = { stats = it }, onLog = { log = it }) }
    LaunchedEffect(client) { onClient(client) }

    // ── 자세
    val conf = LocalConfiguration.current
    val wDp = conf.screenWidthDp
    val hDp = conf.screenHeightDp
    val landscape = wDp > hDp
    val folded = minOf(wDp, hDp) < 600

    // ── 입력 상태
    var korean by remember { mutableStateOf(false) }
    var mods by remember { mutableIntStateOf(0) }
    val hangul = remember { Hangul() }
    var composing by remember { mutableStateOf("") }
    var streamPx by remember { mutableStateOf(0 to 0) }

    // 접힘 커버에서는 **패드 우선**이 기본이다 — 475dp 에 키보드를 상시로 두면
    // 스트림이 200dp 로 줄어 «보면서 친다»가 성립하지 않는다. 칩으로 펴서 쓴다.
    var zone by remember(folded) { mutableStateOf(if (folded) InputZone.PadOnly else InputZone.Full) }
    /** 커버 압축 키보드의 숫자·기호 레이어. */
    var numLayer by remember { mutableStateOf(false) }
    var dock by remember { mutableStateOf(DockState.Collapsed) }
    var absMode by remember { mutableStateOf(false) }
    var tapClick by remember { mutableStateOf(true) }
    var invertScroll by remember { mutableStateOf(false) }
    val sens = 2.0f

    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(token, serverIdx, streamPx, stats.connected, endedByUser) {
        if (!autoConnectAllowed(token, streamPx.first, stats.connected, endedByUser)) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        client.connect(servers[serverIdx].host, servers[serverIdx].port, token, streamPx.first, streamPx.second)
    }
    LaunchedEffect(zoom, cx, cy, stats.connected) {
        if (!stats.connected) return@LaunchedEffect
        val v = 1f / zoom
        client.sendView(cx - v / 2f, cy - v / 2f, v, v)
    }

    fun toStream(off: Offset): Pair<Int, Int>? {
        val (sw, sh) = client.streamSize
        if (sw == 0 || sh == 0 || streamPx.first == 0) return null
        val scale = minOf(streamPx.first.toFloat() / sw, streamPx.second.toFloat() / sh)
        val ox = (streamPx.first - sw * scale) / 2f
        val oy = (streamPx.second - sh * scale) / 2f
        val x = ((off.x - ox) / scale).toInt()
        val y = ((off.y - oy) / scale).toInt()
        return if (x in 0 until sw && y in 0 until sh) x to y else null
    }

    fun send(block: InputBatch.() -> Unit) { client.sendInput(InputBatch().apply(block)) }

    // ── 클릭 — 켜 둔 모디파이어(⌘·opt·^·⇧)를 그 클릭에 싣는다(⌘-클릭 · ⇧-클릭 선택 확장).
    //    ⇧ 는 키보드에서처럼 한 번 쓰면 풀리고, ⌘·opt 는 칩을 끌 때까지 남는다.
    fun clickFlags() = mods and (MacFlags.SHIFT or MacFlags.CONTROL or MacFlags.OPTION or MacFlags.COMMAND)
    fun consumeShift() { if ((mods and MacFlags.SHIFT) != 0) mods = mods and MacFlags.SHIFT.inv() }
    fun click() {
        val f = clickFlags()
        send { mouse(true, 0, f); mouse(false, 0, f) }
        consumeShift()
    }
    fun button(b: Int, down: Boolean) {
        send { mouse(down, b, clickFlags()) }
        if (!down) consumeShift()
    }

    fun commitComposing() {
        val s = hangul.finish(); composing = ""
        if (s.isNotEmpty()) send { text(s) }
    }

    /**
     * 받아쓰기 — 폰 음성 인식으로 친 글자를 맥으로 보낸다. 설계·실측은 input/Dictation.kt.
     * ⚠️ 람다 파라미터를 `text` 로 두면 안 된다 — `send { }` 안에서 `InputBatch.text` 와 겹쳐
     *    **경고 없이 멤버 쪽이 불린다**(이 프로젝트에서 이미 한 번 데인 함정).
     */
    val dictation = remember {
        Dictation(ctx) { said ->
            commitComposing()          // 한글 조합 중이면 먼저 확정 — 섞이면 글자가 깨진다
            send { text("$said ") }    // 마디 사이를 띄운다
        }
    }
    LaunchedEffect(dictation) { onDictation(dictation) }
    // 듣는 도중 한/영을 바꾸면 인식 언어도 따라간다.
    LaunchedEffect(korean) { dictation.retarget(if (korean) "ko-KR" else "en-US") }
    DisposableEffect(dictation) { onDispose { dictation.destroy() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.start(if (korean) "ko-KR" else "en-US")
    }
    fun toggleMic() {
        if (!dictation.hasPermission()) askMic.launch(Manifest.permission.RECORD_AUDIO)
        else dictation.toggle(if (korean) "ko-KR" else "en-US")
    }
    /** 듣는 중일 때 마이크 버튼 아래에 적을 한 마디 — 어느 엔진인지 감추지 않는다. */
    val micNote = when {
        !dictation.listening -> ""
        dictation.engine == DictationEngine.OnDevice -> "기기"
        else -> "망"
    }

    /**
     * 보는 영역 확대/이동. 스트림 위 핀치와 트랙패드 핀치가 **같은 함수**를 쓴다 —
     * 두 군데에 같은 계산을 두면 반드시 어긋난다(이번 세션에서 그 부류로 두 번 데였다).
     * @param fx,fy 제스처가 일어난 면 대비 이동 «비율» — 스트림이든 트랙패드든 크기가 달라도 같게 먹는다.
     */
    fun applyZoom(zoomChange: Float, fx: Float, fy: Float) {
        val nz = (zoom * zoomChange).coerceIn(1f, 6f)
        val v = 1f / nz
        zoom = nz
        cx = (cx - fx * v).coerceIn(v / 2f, 1f - v / 2f)
        cy = (cy - fy * v).coerceIn(v / 2f, 1f - v / 2f)
    }

    fun resetZoom() { zoom = 1f; cx = 0.5f; cy = 0.5f }

    // ── 디스플레이(맥 화면) 전환
    // 서버가 보내 준 목록에서 «지금 보여 주는 것»을 읽는다. 폰은 목록을 들고만 있고 판단은 서버가 한다.
    val curDisplay = stats.displays.firstOrNull { it.isCurrent }
    /** 화면이 하나뿐이면 태그를 숨긴다(null) — 눌러도 아무 일이 없는 컨트롤은 두지 않는다. */
    val displayName = if (stats.displays.size >= 2) curDisplay?.shortLabel ?: "?" else null
    fun nextDisplay() {
        val list = stats.displays
        if (list.size < 2) return
        val i = list.indexOfFirst { it.isCurrent }
        client.selectDisplay(list[(i + 1).mod(list.size)].id)
    }
    // 화면이 바뀌면 줌을 전체로 — 서버도 크롭을 전체로 되돌린다. 옛 화면 기준 좌표로 확대돼 있으면
    // 새 화면의 엉뚱한 곳을 잘라 달라고 하게 된다.
    LaunchedEffect(curDisplay?.id) { resetZoom() }

    /** 맥 제스처 단축키 — 방향키는 Control 만으로 안 먹고 Fn 이 필요하다(실측). */
    fun macGesture(code: Int) {
        val f = MacFlags.CONTROL or MacFlags.FN
        send { key(true, code, f); key(false, code, f) }
    }

    fun onKey(k: KeyDef) {
        when (val a = k.action) {
            is KeyAction.Mod -> { mods = mods xor a.flag; return }
            is KeyAction.HanYeong -> { commitComposing(); korean = !korean; return }
            is KeyAction.NumLayer -> { numLayer = !numLayer; return }
            is KeyAction.Ch -> {
                val shift = (mods and MacFlags.SHIFT) != 0
                val cmdish = (mods and (MacFlags.COMMAND or MacFlags.CONTROL or MacFlags.OPTION)) != 0
                if (korean && !cmdish) {
                    val raw = if (shift) a.ch.uppercaseChar() else a.ch
                    val jamo = Hangul.jamoFor(raw)
                    if (jamo != null) {
                        val out = hangul.input(jamo); composing = hangul.composing
                        if (out.isNotEmpty()) send { text(out) }
                        if (shift) mods = mods and MacFlags.SHIFT.inv()
                        return
                    }
                    commitComposing()
                }
                if (cmdish) {
                    MacKey.forChar(a.ch)?.let { vk ->
                        val flags = mods or (if (shift) MacFlags.SHIFT else 0)
                        send { key(true, vk.first, flags); key(false, vk.first, flags) }
                    }
                } else {
                    // 글자는 항상 유니코드 — 키코드는 맥 IME 가 재해석한다
                    send { text((if (shift) MacKey.shiftChar(a.ch) else a.ch).toString()) }
                }
                if (shift) mods = mods and MacFlags.SHIFT.inv()
            }
            is KeyAction.Vk -> {
                if (a.code == MacKey.DELETE && hangul.hasComposing()) {
                    hangul.backspace(); composing = hangul.composing; return
                }
                if (a.code == MacKey.RETURN || a.code == MacKey.ESCAPE) commitComposing()
                send { key(true, a.code, mods); key(false, a.code, mods) }
                if ((mods and MacFlags.SHIFT) != 0) mods = mods and MacFlags.SHIFT.inv()
            }
            is KeyAction.Mouse -> Unit
        }
    }

    fun quick(name: String) = when (name) {
        "SP<" -> macGesture(MacKey.LEFT)
        "SP>" -> macGesture(MacKey.RIGHT)
        "MC" -> macGesture(MacKey.UP)
        "앱창" -> macGesture(MacKey.DOWN)
        else -> Unit
    }

    // 지역 함수를 그대로 sink 에서 부르면 이름이 겹쳐 자기를 다시 부른다(macGesture).
    val sendMacGesture: (Int) -> Unit = ::macGesture

    /**
     * 스트림 위 제스처. 판정은 [kr.joonlab.foldlab.ui.deckGestures] 한 곳에 있고,
     * 트랙패드와 다른 점만 [GestureProfile.stream] 으로 선언한다.
     */
    val streamSink = object : GestureSink {
        override fun placeCursor(at: Offset) {
            toStream(at)?.let { (x, y) -> send { moveAbs(x, y) } }
        }
        override fun scroll(dy: Int, dx: Int) { send { scroll(dy, dx) } }
        override fun zoom(change: Float, fx: Float, fy: Float) { applyZoom(change, fx, fy) }
        override fun macGesture(macKeyCode: Int) { sendMacGesture(macKeyCode) }
        override fun tap(at: Offset) {
            val pt = toStream(at) ?: return
            if (!tapClick) { send { moveAbs(pt.first, pt.second) }; return }
            val f = clickFlags()
            send { moveAbs(pt.first, pt.second); mouse(true, 0, f); mouse(false, 0, f) }
            consumeShift()
        }
    }
    val streamGestures: Modifier = Modifier.deckGestures(
        GestureProfile.stream(
            invertScroll = invertScroll,
            zoomLevel = zoom,
            tapEnabled = absMode || tapClick
        ),
        streamSink
    )

    @Composable
    fun StreamBox(modifier: Modifier) {
        Box(
            modifier
                .background(Color.Black)
                .onSizeChanged { streamPx = it.width to it.height; client.updateViewport(it.width, it.height) }
                .then(streamGestures)
        ) {
            StreamSurface(client, Modifier.fillMaxSize())
            ModeBadge(korean, mods, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (landscape) {
                TelemetryPill(stats, servers[serverIdx].name,
                              Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            // 아래쪽 알림 띠 — 색으로 무엇인지 가른다.
            //   초록 = 받아쓰는 중(아직 안 보낸 말) · 머스타드 = 한글 조합 중 · 빨강 = 알림
            val hint: Pair<String, Color>? = when {
                dictation.partial.isNotEmpty() -> dictation.partial to DeckColors.ok
                composing.isNotEmpty() -> composing to DeckColors.accent
                dictation.message != null -> dictation.message!! to DeckColors.bad
                else -> null
            }
            if (hint != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .clip(RoundedCornerShape(4.dp)).background(hint.second)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(hint.first, color = DeckColors.bg, fontSize = 15.sp,
                         fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                         maxLines = 2)
                }
            }
            if (!stats.connected) {
                ConnectOverlay(servers[serverIdx], log,
                    onPick = { serverIdx = (serverIdx + 1) % servers.size
                               prefs.edit().putInt("server", serverIdx).apply() },
                    onConnect = {
                        endedByUser = false
                        if (token.isBlank()) showSetup = true
                        else client.connect(servers[serverIdx].host, servers[serverIdx].port,
                                            token, streamPx.first, streamPx.second)
                    },
                    onSetup = { showSetup = true })
            }
        }
    }

    // 붙어 있는 동안 대상을 바꾼다. 전에는 전환 UI 가 ConnectOverlay 안에만 있었고
    // 그 오버레이는 «끊겼을 때만» 떠서, 붙어 있으면 바꿀 방법이 아예 없었다.
    //
    // 새 대상으로 **직접** 건다. `connect` 가 안에서 먼저 끊어 준다(keepLast — 오버레이가 깜빡이지 않는다).
    fun nextServer() {
        endedByUser = false
        serverIdx = (serverIdx + 1) % servers.size
        prefs.edit().putInt("server", serverIdx).apply()
        if (token.isNotBlank() && streamPx.first > 0) {
            client.connect(servers[serverIdx].host, servers[serverIdx].port,
                           token, streamPx.first, streamPx.second)
        }
    }

    // 칩 목록. **id 는 고정, label 만 상태를 따라 바뀐다** — 둘을 겸하면 라벨이 바뀌는 칩이
    // 자기 식별자를 잃는다(zone 칩이 한 번만 먹던 버그, 2026-09-22).
    val chips = listOf(
        ChipSpec("SP<", "SP<"), ChipSpec("SP>", "SP>"), ChipSpec("MC", "MC"), ChipSpec("앱창", "앱창"),
        ChipSpec("zone", zone.label, zone != InputZone.Off),
        ChipSpec("abs", "ABS", absMode),
        ChipSpec("han", "한/영", korean),
        ChipSpec("tap", "TAP", tapClick),
        ChipSpec("cmd", "⌘", (mods and MacFlags.COMMAND) != 0),
        ChipSpec("opt", "opt", (mods and MacFlags.OPTION) != 0),
        ChipSpec("zoom", if (zoom > 1.02f) "⤢%.1fx".format(zoom) else "⤢1x", zoom > 1.02f),
        ChipSpec("invert", "↕반전", invertScroll),
        ChipSpec("sync", "SYNC"),
        ChipSpec("end", "END")
    )
    val onChip: (String) -> Unit = { id ->
        when (id) {
            "SP<", "SP>", "MC", "앱창" -> quick(id)
            "zone" -> zone = zone.next()
            "abs" -> absMode = !absMode
            "han" -> { commitComposing(); korean = !korean }
            "tap" -> tapClick = !tapClick
            "cmd" -> mods = mods xor MacFlags.COMMAND
            "opt" -> mods = mods xor MacFlags.OPTION
            "invert" -> invertScroll = !invertScroll
            "sync" -> client.requestKeyframe()
            "end" -> { endedByUser = true; client.disconnect() }
            // 더블탭을 «맥 더블클릭»에 내줬으므로, 전체 보기로 돌아오는 길은 이 칩 하나다.
            "zoom" -> when {
                zoom < 1.3f -> zoom = 1.5f
                zoom < 1.8f -> zoom = 2f
                zoom < 2.5f -> zoom = 3f
                else -> resetZoom()
            }
            else -> Unit
        }
    }

    // ── 배치
    if (landscape) {
        // 가로로 돌리는 건 «크게 보겠다»는 뜻 — 기본은 안 가린다
        Box(Modifier.fillMaxSize().background(DeckColors.bg).safeDrawingPadding()) {
            StreamBox(Modifier.fillMaxSize())
            FloatingDock(
                state = dock, onState = { dock = it },
                korean = korean, activeMods = mods, sensitivity = sens,
                onKey = ::onKey,
                onMove = { dx, dy -> send { moveRel(dx, dy) } },
                onScroll = { dy, dx -> send { scroll(dy, dx) } },
                onClick = ::click,
                onButton = ::button,
                onZoom = ::applyZoom,
                onMacGesture = ::macGesture,
                zoomLevel = zoom,
                onMic = ::toggleMic,
                micOn = dictation.listening,
                micNote = micNote,
                // ⚠️ zone 은 뺀다 — 세로 배치(스트림/키보드/패드 비율)를 고르는 토글이라
                //    가로에서는 아무 효과가 없다. 죽은 컨트롤을 보여 주면 «눌렀는데 안 된다»가 된다.
                // 제스처 칩(SP< SP> MC 앱창)도 이 목록이 나른다 — onChip 이 이미 quick() 으로 보낸다.
                chips = chips.filterNot { it.id == "zone" },
                onChip = onChip,
                serverName = servers[serverIdx].name,
                connected = stats.connected,
                onServerTap = ::nextServer,
                displayName = displayName,
                onDisplayTap = ::nextDisplay
            )
        }
    } else {
        Column(Modifier.fillMaxSize().background(DeckColors.bg).safeDrawingPadding()) {
            StreamBox(Modifier.fillMaxWidth().weight(1f))
            TelemetryBar(stats, chips, onChip, Modifier.fillMaxWidth().height(22.dp),
                         serverName = servers[serverIdx].name, onServerTap = ::nextServer,
                         displayName = displayName, onDisplayTap = ::nextDisplay)
            if (zone == InputZone.Full) {
                // 간격·글자를 줄여 키보드가 차지하던 높이를 트랙패드로 넘겼다.
                if (folded) {
                    CyberdeckKeyboardCompact(korean, mods, numLayer, ::onKey,
                        Modifier.fillMaxWidth().weight(0.80f))
                } else {
                    CyberdeckKeyboard(korean, mods, ::onKey, Modifier.fillMaxWidth().weight(0.62f))
                }
            }
            if (zone != InputZone.Off) {
                TrackpadStrip(
                    sensitivity = sens,
                    onMove = { dx, dy -> send { moveRel(dx, dy) } },
                    onScroll = { dy, dx -> send { scroll(dy, dx) } },
                    onClick = ::click,
                    onButton = ::button,
                    onZoom = ::applyZoom,
                    onMacGesture = ::macGesture,
                    zoomLevel = zoom,
                    onMic = ::toggleMic,
                    micOn = dictation.listening,
                    micNote = micNote,
                    modifier = Modifier.fillMaxWidth()
                        .weight(
                            when {
                                zone == InputZone.PadOnly -> 0.60f
                                folded -> 0.45f   // 커버는 압축 키보드가 5행이라 패드를 조금 더 준다
                                else -> 0.36f
                            }
                        )
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                    compact = folded
                )
            }
        }
    }

    if (showSetup) {
        TokenDialog(token, servers[serverIdx], onDismiss = { showSetup = false }) { t ->
            token = t.trim(); prefs.edit().putString("token", token).apply(); showSetup = false
            endedByUser = false
            if (token.isNotBlank())
                client.connect(servers[serverIdx].host, servers[serverIdx].port,
                               token, streamPx.first, streamPx.second)
        }
    }
}

/**
 * 스트림 우상단 상태 배지.
 *
 * 맥 화면을 보는 동안에는 키캡을 안 보게 되므로 «지금 무슨 상태인가»를 시선이 가 있는 자리에 둔다.
 * 한/영 + **지금 눌려 있는 모디파이어**. PAD·화면만 모드에서는 키보드가 아예 없어 여기가 유일한 표시다.
 */
@Composable
private fun ModeBadge(korean: Boolean, mods: Int, modifier: Modifier) {
    // `opt` 는 글자라 글리프끼리 붙여 놓으면 읽히지 않는다 — 사이를 띄운다.
    val active = buildList {
        if (mods and MacFlags.SHIFT != 0) add("⇧")
        if (mods and MacFlags.CONTROL != 0) add("^")
        if (mods and MacFlags.OPTION != 0) add("opt")
        if (mods and MacFlags.COMMAND != 0) add("⌘")
    }.joinToString(" ")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BadgePill(if (korean) "한" else "EN", korean, "badge:mode")
        if (active.isNotEmpty()) BadgePill(active, true, "badge:mods")
    }
}

@Composable
private fun BadgePill(text: String, on: Boolean, tag: String) {
    Box(
        Modifier.testTag(tag)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) DeckColors.accent else Color(0x99202631))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text,
             color = if (on) DeckColors.bg else DeckColors.textDim,
             fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

/**
 * 스트림 위에 얹는 상태 알약. 서버 이름도 여기 넣는다 — 가로(전체화면)에는 텔레메트리 줄이 없어서
 * 「어느 맥에 붙었나」를 볼 데가 없었다.
 *
 * ⚠️ **여기에 탭을 달지 않는다.** 스트림 위 오버레이라 빗나간 탭이 그대로 맥으로 가 클릭이 된다
 *    (2026-09-22 실제로 한 번 냈다). 전환은 세로 텔레메트리 줄의 서버 표시에서 한다.
 */
@Composable
private fun TelemetryPill(stats: DeckStats, serverName: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xB30D1117))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            if (stats.connected)
                "%s · T1 %.2fMbps %dfps net %dms"
                    .format(serverName, stats.bitrateBps / 1_000_000.0, stats.fps, stats.latencyMs)
            else "$serverName · 연결 안 됨",
            color = if (stats.connected) DeckColors.textDim else DeckColors.bad,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1
        )
    }
}

@Composable
private fun StreamSurface(client: DeckClient, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { ctx ->
        SurfaceView(ctx).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { client.attachSurface(h.surface) }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { client.attachSurface(h.surface) }
                override fun surfaceDestroyed(h: SurfaceHolder) { client.attachSurface(null) }
            })
        }
    })
}

@Composable
private fun ConnectOverlay(
    server: DeckServer, log: String,
    onPick: () -> Unit, onConnect: () -> Unit, onSetup: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xCC0D1117)),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("사이버덱", color = DeckColors.accent, fontSize = 26.sp,
             fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("연결 안 됨", color = DeckColors.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick) {
                Text("${server.name}  ${server.host}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Button(onClick = onConnect) { Text("접속") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSetup) { Text("토큰 설정", fontSize = 12.sp) }
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(log, color = DeckColors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TokenDialog(
    initial: String, server: DeckServer,
    onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("deckd 토큰") },
        text = {
            Column {
                Text("맥에서:  cat ~/.config/deckd/token",
                     fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = DeckColors.textDim)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                Text("대상: ${server.name} ${server.host}:${server.port}",
                     fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DeckColors.textDim)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t) }) { Text("저장하고 접속") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
