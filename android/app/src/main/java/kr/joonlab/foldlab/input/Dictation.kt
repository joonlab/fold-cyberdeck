package kr.joonlab.foldlab.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 받아쓰기 — 폰의 음성 인식으로 친 글자를 맥으로 보낸다.
 *
 * ## «갤럭시 자체 음성 인식»은 없다 (2026-09-22 실측)
 *
 * 이 기기(SM-F971N / Android 17)에 등록된 `RecognitionService` 는 **구글 둘뿐**이다:
 * ```
 * com.google.android.as/...AiAiSpeechRecognitionService    ← 온디바이스
 * com.google.android.tts/...GoogleTTSRecognitionService     ← 기본값
 * ```
 * Bixby 온디바이스 팩(`bixby.ondevice.kokr`)은 깔려 있지만 **공개 RecognitionService 를 안 내놓는다.**
 * 삼성 키보드의 마이크도 `default_voice_input_method` 가 구글 음성 IME 라 결국 같은 엔진으로 간다.
 * 즉 앱에서 부를 수 있는 길은 표준 [SpeechRecognizer] 하나다.
 *
 * ## 설계
 *
 * - **온디바이스 우선.** 받아쓴 내용이 업무·고객사 얘기일 수 있다. 언어팩이 없으면 그때만 기본 엔진으로
 *   내려가고, **지금 어느 쪽인지 칩에 표시한다**(`음성·기기` / `음성·망`) — 모르는 새 망으로 나가지 않게.
 * - **문장 단위로 보낸다.** 부분 결과는 폰에만 띄운다. 맥에 실시간으로 쓰면 고쳐 쓸 때마다 ⌫ 를
 *   보내야 하고, 그 사이 사용자가 친 글자와 섞인다.
 * - **연속 인식.** 인식기는 한 마디마다 끝나므로 [listening] 이 켜져 있는 동안 다시 건다.
 *
 * ## 🚨 한 마디마다 «새로 만들면» 안 된다 (2026-09-22 실측)
 *
 * 처음엔 다시 걸 때마다 `destroy()` 후 새 인식기를 만들었다. 로그가 이렇게 찍혔다:
 * ```
 * 50.704 error=7  (NO_MATCH)   → 150ms 뒤 재시작
 * 50.855 open (새 인식기)
 * 50.856 error=11 (SERVER_DISCONNECTED)   ← 0.6초 만에 죽는다
 * ```
 * 온디바이스 엔진(SODA)은 `blockingDisconnect`/`reconnect` 가 무거워서 그 사이에 새로 붙으면
 * 레이스가 난다. → **인식기 인스턴스는 하나를 재사용**하고 `startListening` 만 다시 부른다.
 * 11 번은 치명적 오류가 아니라 **인스턴스를 버리고 좀 쉬었다 다시 붙으면 되는** 오류다.
 *
 * ## 멈추는 조건은 정해 둔다
 *
 * 무음이면 계속 다시 걸리므로 상한이 없으면 배터리만 먹는다.
 * **연속 하드 실패 [MAX_HARD_FAILS] 회** 또는 **[IDLE_STOP_MS] 동안 말이 없으면** 스스로 끈다.
 * `ERROR_RECOGNIZER_BUSY` 도 [BUSY_TRIES] 회까지만 — 누가 인식기를 쥐고 있으면 영원히 안 놓는다.
 *
 * ⚠️ [SpeechRecognizer] 는 **메인 스레드에서만** 만들고 부르고 없앨 수 있다.
 */
enum class DictationEngine { None, OnDevice, Default }

class Dictation(
    private val ctx: Context,
    /**
     * 온디바이스를 다시 노려보기까지의 시간. 테스트가 줄여 쓴다.
     * ⚠️ [onFinal] 앞에 둔다 — trailing lambda 는 **마지막 파라미터**에 붙는다.
     */
    private val onDeviceRetryMs: Long = ONDEVICE_RETRY_MS,
    /** 아무 콜백도 없으면 «먹통»으로 보는 시간. 테스트가 줄여 쓴다. */
    private val wedgeTimeoutMs: Long = WEDGE_TIMEOUT_MS,
    /** 온디바이스가 `ready` 뒤 이만큼 RMS 를 하나도 안 주면 먹통으로 본다. 테스트가 줄여 쓴다. */
    private val noAudioMs: Long = NO_AUDIO_MS,
    /**
     * 온디바이스를 **먼저** 노릴지. 기본은 [PREFER_ON_DEVICE](= 온디바이스 우선, 21차).
     * 온디바이스 경로 자체를 재는 테스트는 이걸 true 로 줘야 한다 —
     * 기본값이 바뀌어도 그 테스트들이 같은 경로를 재도록 명시한다(20차에 망 우선일 때 배운 것).
     */
    private val preferOnDeviceDefault: Boolean = PREFER_ON_DEVICE,
    /** 확정된 한 마디. 여기서 맥으로 보낸다. */
    private val onFinal: (String) -> Unit
) : RecognitionListener {

    /** 지금 듣고 있나. Compose 상태라 그대로 읽으면 리컴포지션이 따라온다. */
    var listening by mutableStateOf(false); private set
    var engine by mutableStateOf(DictationEngine.None); private set
    /** 아직 확정 안 된 말. 폰 화면에만 띄운다. */
    var partial by mutableStateOf(""); private set
    /** 사용자에게 보여줄 한 줄(권한 없음·언어팩 없음 등). */
    var message by mutableStateOf<String?>(null); private set
    /** `onReadyForSpeech` 를 받은 횟수 — «정말로 마이크가 열렸나»의 증거. 켤 때 0 으로. */
    var readyCount by mutableStateOf(0); private set
    /** 인식기 인스턴스를 만든 횟수 — 껐다 켤 때 늘어나면 안 된다(그게 BUSY 의 원인이었다). */
    var createdCount by mutableStateOf(0); private set

    private var sr: SpeechRecognizer? = null
    private var locale = "ko-KR"
    /** 온디바이스로 시도할지. 언어팩이 없어 실패하면 내려간다. */
    /**
     * 온디바이스를 **먼저** 쓸지. 기본은 [PREFER_ON_DEVICE].
     * 먹통이면 [noAudioCheck] 가 2초 안에 false 로 내리고 [noOnDeviceAt] 에 기억한다.
     */
    private var preferOnDevice = preferOnDeviceDefault
    /**
     * 온디바이스에 «없다»고 확인된 언어와 그 시각.
     *
     * 🚨 **영구 블랙리스트로 두면 안 된다.** 처음엔 `Set` 이었는데, en-US 팩이 실제로 내려온 뒤에도
     * 앱이 다시 안 봐서 **계속 망 엔진으로 갔다**(2026-09-22 신고 —
     * `installed=[en-US, ko-KR]` 인데도 `새 인식기 engine=Default locale=en-US`).
     * → [ONDEVICE_RETRY_MS] 지나면 다시 노려보고, 내려받기가 끝나면 즉시 푼다.
     */
    private val noOnDeviceAt = mutableMapOf<String, Long>()
    /** 망 엔진으로 내려간 시각 — 자리가 빌 수 있으니 이따가 온디바이스를 다시 시도한다. */
    private var fellBackAt = 0L
    /** `startListening` 을 부르고 아직 아무 답(ready/error)도 못 받았나. */
    private var awaitingReady = false
    /** 인식기에서 마지막으로 «뭐라도» 온 시각. 먹통 판정에 쓴다. */
    private var lastCallbackAt = 0L
    /**
     * 내가 일부러 `cancel()` 을 불렀으니 바로 뒤에 올 오류는 «취소에 대한 답»이다.
     * 🚨 `stop()` 은 `listening=false` 라 자동으로 걸러지지만, **[retarget] 은 켠 채로 취소**하므로
     * 이 표시가 없으면 한/영을 누를 때마다 「음성 인식 오류」가 떴다(2026-09-22 신고).
     */
    private var ignoreNextError = false
    private val main = Handler(Looper.getMainLooper())
    /** 결과 없이 이어진 «하드» 실패 수. 말이 없어 생기는 NO_MATCH 는 여기 안 센다. */
    private var hardFails = 0
    /** 연속 BUSY 수 — 누가 인식기를 쥐고 있을 때 무한히 다시 걸지 않도록. */
    private var busyStreak = 0
    /** 마지막으로 말이 잡힌 시각. 너무 오래 조용하면 스스로 끈다. */
    private var lastVoiceAt = 0L
    /** 분절 세션(마이크를 열어 둔 채 한 마디씩)을 쓸 수 있나. 안 되면 재시작 방식으로 내려간다. */
    private var segmented = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    /** 분절 세션으로 실제 결과가 온 적 있나 — 한 번도 없으면 지원 안 하는 것으로 본다. */
    var segmentWorks = false; private set
    /** BUSY 를 실제로 «처리»한 횟수 — 삼켜지면 안 오른다. 이름이 아니라 증거로 재려고 연다. */
    var busyCount = 0; private set
    /** 빈 분절이 연달아 온 횟수. 상한을 넘으면 분절 세션을 끈다. */
    private var emptySegments = 0
    /** 지금 분절 세션을 쓰기로 돼 있나 — 진단·회귀 테스트용 관찰 지점. */
    val segmentedOn get() = segmented
    /** 직전에 보낸 말과 시각 — 같은 말이 두 경로로 오면 맥에 두 번 찍힌다. */
    private var lastText = ""
    private var lastAt = 0L

    /**
     * 지금 쓰고 싶은 엔진. [engine] 과 달라지면 인스턴스를 갈아타야 한다.
     *
     * 🚨 예전에는 [ensure] 가 «있으면 그대로 쓴다»였다. 그래서 한 번 BUSY 로 망 엔진에 내려가면
     * 다시 켜도 **그 인스턴스를 재사용해 영영 망이었다** — 칩은 계속 `음성·망` 이고,
     * 사용자는 「왜 온디바이스가 아니냐」고 묻게 된다(2026-09-22 신고).
     */
    val preferredEngine: DictationEngine
        get() = if (preferOnDevice && !blockedOnDevice(locale) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        ) DictationEngine.OnDevice else DictationEngine.Default

    /** 이 언어는 «아직» 온디바이스에 없다고 봐야 하나. 시간이 지나면 다시 본다. */
    private fun blockedOnDevice(loc: String): Boolean {
        val at = noOnDeviceAt[loc] ?: return false
        if (System.currentTimeMillis() - at > onDeviceRetryMs) { noOnDeviceAt.remove(loc); return false }
        return true
    }

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggle(locale: String) {
        if (listening) stop() else start(locale)
    }

    fun start(locale: String) {
        Log.i(TAG, "start($locale) listening=$listening perm=${hasPermission()}")
        if (listening) return
        if (!hasPermission()) { message = "마이크 권한이 없다"; return }
        this.locale = locale
        message = null
        partial = ""
        preferOnDevice = preferOnDeviceDefault
        hardFails = 0
        busyStreak = 0
        readyCount = 0
        lastVoiceAt = System.currentTimeMillis()
        lastCallbackAt = lastVoiceAt
        listening = true
        listen()
    }

    /** 듣는 도중 한/영이 바뀌면 그 언어로 다시 건다 — 안 그러면 영문 모드로 한국어를 받아쓴다. */
    fun retarget(locale: String) {
        if (!listening || locale == this.locale) return
        Log.i(TAG, "retarget → $locale")
        this.locale = locale
        partial = ""
        hardFails = 0
        busyStreak = 0
        // 🚨 인스턴스를 버리지 않는다 — 언어는 intent 에 실리고, 여기서 새로 만들면 재연결 레이스로
        //    BUSY/SERVER_DISCONNECTED 가 나서 **조용히 망 엔진으로 내려간다**(실측).
        //    엔진 종류가 바뀌어야 할 때만 [ensure] 가 알아서 갈아탄다.
        ignoreNextError = true
        runCatching { sr?.cancel() }
        listen(150)
    }

    /**
     * 듣기를 멈춘다.
     *
     * 🚨 **인스턴스를 파괴하지 않는다.** 껐다가 곧 다시 켜면(칩을 두 번 누르면) 온디바이스 엔진이
     * 아직 옛 세션을 붙들고 있어 `ERROR_RECOGNIZER_BUSY(8)` 가 난다 — 실측: stop 0.6초 뒤 재시작이
     * 17ms 만에 BUSY. 인스턴스는 살려 두고 `cancel()` 만 한다(마이크는 놓는다).
     * 정말로 놓아야 할 때는 [destroy] — 앱이 배경으로 갈 때가 그때다.
     */
    fun stop() {
        Log.i(TAG, "stop")
        listening = false
        partial = ""
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.cancel() }
        // 🚨 **온디바이스 인식은 동시 세션이 1개**다(실측 — 서비스가
        //    `#startListening received when the service's capacity is full` 이라고 찍는다).
        //    인스턴스를 들고 있으면 그 한 자리를 계속 차지해 다른 쪽이 못 쓴다.
        //    그렇다고 끄자마자 파괴하면 곧 다시 켤 때 BUSY 가 난다(재연결 레이스).
        //    → **잠깐 들고 있다가, 정말 노는 것 같으면 놓는다.**
        main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
    }

    /** 걸었는데 아무 답이 없으면 «자리가 찼다»와 같이 취급한다. */
    private val readyWatchdog = Runnable {
        if (!listening || !awaitingReady) return@Runnable
        Log.w(TAG, "시작 응답 없음 — 인식기가 조용히 무시했다")
        awaitingReady = false
        onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    /**
     * 🚨 인식기가 **`ready` 는 주고 소리는 안 듣는** 상태로 빠질 때가 있다(2026-09-22 실측).
     * 이때 `dumpsys audio` 에 녹음 세션이 **아예 안 잡히고**, 콜백도 하나도 안 온다 —
     * 앱은 «켜져 있다»고 믿으며 영원히 기다린다. 사용자에겐 「갑자기 안 된다」로 보인다.
     * 정상일 때는 무음이어도 2초마다 segment/error 가 오므로, **아무것도 없는 상태**는 먹통이다.
     */
    private val aliveCheck = object : Runnable {
        override fun run() {
            if (!listening) return
            val idle = System.currentTimeMillis() - lastCallbackAt
            if (idle >= wedgeTimeoutMs) {
                Log.w(TAG, "${idle}ms 동안 인식기가 아무 말이 없다 — 먹통으로 본다")
                wedged()
                return
            }
            main.postDelayed(this, ALIVE_CHECK_MS)
        }
    }

    /** `ready` 뒤 RMS 를 하나라도 받았나. 온디바이스 먹통은 `ready` 만 주고 이게 영영 false 다. */
    private var heardAudio = false

    /**
     * 🚨 온디바이스가 **`ready` 만 주고 소리를 안 보내는** 먹통을 [NO_AUDIO_MS] 에 가른다.
     *
     * 2026-09-23 실측: 먹통일 때 `com.google.android.as` 는 마이크를 열고 소리를 파이프에 붓지만
     * 그걸 읽을 인식 엔진(SODA)이 붙지 않는다(`Initialize Soda` 가 안 뜨고
     * `IO error writing to pipe … awaitSpace`). 건강하면 첫 RMS 가 0.8초쯤 온다.
     * [aliveCheck] 만으로는 `ready` 자체가 타이머를 되돌려 8초를 버렸다.
     * 앱은 다른 앱(서비스)을 재시작할 권한이 없다 — 할 수 있는 건 **빨리 알아채고 빠지는 것**뿐이다.
     */
    private val noAudioCheck = Runnable {
        if (!listening || heardAudio || engine != DictationEngine.OnDevice) return@Runnable
        Log.w(TAG, "ready 뒤 ${noAudioMs}ms 동안 소리가 안 온다 — 온디바이스 먹통으로 본다")
        wedged()
    }

    /** 먹통으로 판정됐다 — 온디바이스면 기억해 두고 망으로, 아니면 같은 엔진으로 다시 건다. */
    private fun wedged() {
        if (engine == DictationEngine.OnDevice && preferOnDevice) {
            preferOnDevice = false
            fellBackAt = System.currentTimeMillis()
            // 🚨 **기억한다.** 전에는 이 판정이 그 세션 안에서만 살아 있었고
            //    `start()` 가 매번 `preferOnDevice = true` 로 되돌려서,
            //    켤 때마다 먹통 온디바이스에 다시 걸고 8초를 버렸다(2026-09-23 사용자 신고).
            //    이미 있는 만료 장치를 쓴다 — 60초 뒤에는 다시 노려본다.
            //    (13차 교훈: 실패를 **영구로** 기억하면 팩이 고쳐져도 영영 망이 된다)
            noOnDeviceAt[locale] = System.currentTimeMillis()
            message = "온디바이스 인식기가 먹통이라 기본 엔진으로 간다"
        } else {
            message = "인식기가 응답만 하고 듣지 않는다 — 다시 건다"
        }
        drop(); listen(300)
    }

    private fun touch() { lastCallbackAt = System.currentTimeMillis() }

    private val releaseIdle = Runnable {
        if (!listening) { Log.i(TAG, "유휴 해제 — 온디바이스 자리를 놓는다"); close(); engine = DictationEngine.None }
    }

    /** 인스턴스까지 놓는다. 앱이 배경으로 가거나 화면이 사라질 때. */
    fun destroy() {
        Log.i(TAG, "destroy")
        listening = false
        partial = ""
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.cancel() }
        close()
        engine = DictationEngine.None
    }

    // ── 내부

    private fun close() {
        runCatching { sr?.destroy() }
        sr = null
    }

    /** 인식기 인스턴스를 만든다(있으면 그대로 쓴다). 여기서 «새로 만드는 일»은 드물어야 한다. */
    /** 인식기 인스턴스를 준비한다. **원하는 엔진과 다르면 갈아탄다** — 그냥 재사용하면 안 된다. */
    private fun ensure(): SpeechRecognizer? {
        val want = preferredEngine
        sr?.let { if (engine == want) return it else { Log.i(TAG, "엔진 교체 $engine → $want"); close() } }
        val onDevice = want == DictationEngine.OnDevice
        val r = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else {
                if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    message = "이 기기에 음성 인식기가 없다"; listening = false; return null
                }
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "인식기 생성 실패", e)
            message = "인식기를 못 만들었다"; listening = false; return null
        }
        r.setRecognitionListener(this)
        sr = r
        createdCount++
        engine = want
        Log.i(TAG, "새 인식기 engine=$engine locale=$locale")
        return r
    }

    /** 인스턴스를 버린다. 서버가 끊긴 뒤에는 이걸 하고 «쉬었다» 다시 붙어야 한다. */
    private fun drop() {
        main.removeCallbacksAndMessages(null)
        close()
    }

    /** [delayMs] 뒤에 듣기를 (다시) 건다. 인스턴스는 재사용한다. */
    private fun listen(delayMs: Long = 0) {
        if (!listening) return
        main.removeCallbacksAndMessages(null)
        val go = Runnable {
            if (!listening) return@Runnable
            // 자리가 차서 망으로 내려갔더라도 영영 거기 머무르지 않는다 — 이따가 다시 노려본다.
            // 단 «망 우선»으로 정해 둔 동안에는 되돌아가지 않는다 — 그러면 8초를 또 버린다.
            if (preferOnDeviceDefault && !preferOnDevice && fellBackAt > 0L &&
                System.currentTimeMillis() - fellBackAt > onDeviceRetryMs) {
                Log.i(TAG, "온디바이스 재시도")
                preferOnDevice = true
                busyStreak = 0
                fellBackAt = 0L
            }
            if (System.currentTimeMillis() - lastVoiceAt > IDLE_STOP_MS) {
                message = "말이 없어 껐다"; stop(); return@Runnable
            }
            val r = ensure() ?: return@Runnable
            runCatching { r.startListening(intent()) }
                .onSuccess {
                    // 🚨 인식 서비스는 자리가 없으면 **조용히 무시한다** —
                    //    로그에 `#startListening received when the service's capacity is full
                    //    - ignoring this call` 만 찍고 ready 도 error 도 안 준다.
                    //    그러면 «켜져 있다고 믿으며 아무것도 안 듣는» 상태가 된다. 그래서 답을 기다린다.
                    awaitingReady = true
                    main.postDelayed(readyWatchdog, READY_TIMEOUT_MS)
                }
                .onFailure {
                    Log.w(TAG, "startListening 실패", it)
                    hardFail("듣기를 시작하지 못했다")
                }
        }
        if (delayMs <= 0) go.run() else main.postDelayed(go, delayMs)
    }

    /** 하드 실패 — 상한을 넘으면 스스로 끈다(무한 재시도 금지). */
    /**
     * 맥으로 한 마디 보낸다.
     *
     * 🚨 분절 세션은 `onSegmentResults` 로 주고, 세션이 끝날 때 `onResults` 로 **같은 말을 또** 줄 수 있다.
     * 그대로 두면 맥에 두 번 찍힌다. 짧은 창 안의 같은 말만 접는다 — 「네. 네.」처럼 진짜로 두 번
     * 말한 것까지 삼키면 안 되므로 시간 조건을 둔다.
     */
    private fun emit(t: String) {
        val now = System.currentTimeMillis()
        if (t == lastText && now - lastAt < DEDUPE_MS) { Log.i(TAG, "중복 무시: $t"); return }
        lastText = t; lastAt = now
        lastVoiceAt = now; hardFails = 0
        onFinal(t)
    }

    /**
     * 온디바이스 언어 모델을 내려받아 달라고 시스템에 건다.
     * 지금 당장은 안 되지만, 다음부터는 기기에서 돌 수 있다.
     */
    private fun requestModelDownload(loc: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return
        runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, loc)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                r.triggerModelDownload(i, { it.run() }, object : android.speech.ModelDownloadListener {
                    override fun onScheduled() { Log.i(TAG, "$loc 모델 내려받기 예약됨") }
                    override fun onProgress(percent: Int) {}
                    override fun onSuccess() {
                        // 내려왔으면 바로 다시 쓸 수 있게 막아 둔 것을 푼다.
                        Log.i(TAG, "$loc 모델 준비됨 — 온디바이스 재시도 허용")
                        main.post { noOnDeviceAt.remove(loc) }
                    }
                    override fun onError(error: Int) { Log.w(TAG, "$loc 모델 내려받기 실패 $error") }
                })
            } else {
                r.triggerModelDownload(i)
            }
            // 🚨 바로 놓는다 — 온디바이스 동시 세션은 1개라 들고 있으면 정작 받아쓰기가 막힌다.
            main.postDelayed({ runCatching { r.destroy() } }, 1_500)
            Log.i(TAG, "$loc 모델 내려받기 요청")
        }.onFailure { Log.w(TAG, "모델 내려받기 요청 실패", it) }
    }

    private fun hardFail(reason: String) {
        hardFails++
        if (hardFails >= MAX_HARD_FAILS) {
            message = "$reason (${hardFails}회 연속) — 껐다"
            stop()
        } else {
            message = reason
            drop()
            listen(600L * hardFails)   // 점점 느리게 다시 붙는다
        }
    }

    /** [forEngine] 을 받는 이유는 테스트가 «망으로 내려간 경우»도 볼 수 있어야 하기 때문이다. */
    internal fun intent(forEngine: DictationEngine = engine) =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 말 사이가 잠깐 비었다고 끊지 않게. (엔진이 무시할 수도 있는 힌트다)
        // ⚠️ 이 계열 extra 는 **Int** 다. Long 을 넣으면 서비스가 조용히 무시한다.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
        // 🚨 분절 세션은 **온디바이스에서만** 쓴다.
        //    8차에 넣을 때 온디바이스(SODA)로만 실측했는데 엔진과 무관하게 걸고 있었다.
        //    2026-09-23 실측: 온디바이스가 먹통이 돼 망 엔진으로 내려가면
        //    `onSegmentResults` 가 **빈 채로** 오고(`segment=null`) 곧 `error=7`(NO_MATCH) —
        //    `speech begin`/`speech end` 로 말은 잡히는데 글자가 하나도 안 나온다.
        //    즉 폴백이 있으나 마나였다. 망은 평범한 `onResults` 경로로 받는다.
        if (segmented && forEngine == DictationEngine.OnDevice) {
            // 분절 세션 — 마이크를 열어 둔 채 한 마디씩 onSegmentResults 로 내려준다.
            // 값은 «세션이 언제 끝나는지 정하는 extra 의 이름»이다.
            // 무음 길이로 마디를 가르고, 세션 자체는 stop 할 때까지 이어진다.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                     RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
        }
    }

    // ── RecognitionListener

    override fun onReadyForSpeech(params: Bundle?) {
        message = null; busyStreak = 0; readyCount++; awaitingReady = false; ignoreNextError = false
        touch(); main.postDelayed(aliveCheck, ALIVE_CHECK_MS)
        heardAudio = false
        main.removeCallbacks(noAudioCheck)
        if (engine == DictationEngine.OnDevice) main.postDelayed(noAudioCheck, noAudioMs)
        Log.i(TAG, "ready")
    }
    override fun onBeginningOfSpeech() { touch(); heardAudio = true; Log.i(TAG, "speech begin") }
    override fun onRmsChanged(rmsdB: Float) { touch(); heardAudio = true }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { touch(); Log.i(TAG, "speech end") }
    override fun onEvent(eventType: Int, params: Bundle?) { Log.i(TAG, "event $eventType") }

    /** 분절 세션에서 한 마디가 확정될 때마다 온다 — 마이크는 계속 열려 있다. */
    override fun onSegmentResults(segmentResults: Bundle) {
        if (!listening) return
        touch()
        val t = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        Log.i(TAG, "segment=" + t)
        if (t.isNullOrBlank()) {
            // 분절이 비어도 부분결과가 남아 있으면 그게 사실상 그 마디다.
            if (flushPartial()) { emptySegments = 0; return }
            // 🚨 빈 분절이 연달아 오면 그 엔진은 분절 세션을 **못 하는 것**이다.
            //    전에는 `segmentWorks` 를 세워 두기만 하고 아무도 안 읽어서, 주석이 약속한
            //    「안 되면 지원 안 하는 것으로 본다」가 **한 번도 일어나지 않았다**(죽은 로직).
            if (++emptySegments >= MAX_EMPTY_SEGMENTS && !segmentWorks) {
                Log.w(TAG, "빈 분절 ${emptySegments}회 — 이 엔진은 분절 세션을 못 한다. 끈다")
                segmented = false
                drop()
                listen(300)
            }
            return
        }
        emptySegments = 0
        segmentWorks = true
        partial = ""
        if (!t.isNullOrBlank()) emit(t.trim())
        // 다시 걸지 않는다 — 세션은 계속된다.
    }

    override fun onEndOfSegmentedSession() {
        Log.i(TAG, "segmented session end")
        listen(250)
    }

    /**
     * 마지막 부분 결과를 **확정으로 써서** 내보낸다.
     *
     * 🚨 2026-09-23 실측: 엔진이 말을 제대로 알아듣고 `onPartialResults` 로 내려 준 뒤
     *    `onResults` 를 **빈 채로** 주는 경우가 있다. 그러면 우리 코드가 통째로 버려서
     *    「말은 하는데 맥에 아무것도 안 들어온다」가 된다.
     *      partial=말해 보겠습니다  →  final=null   (두 번 재현)
     *    부분 결과는 `speech end` 뒤에도 한 번 더 오므로 사실상 그게 최종이다.
     *
     * 중복은 [emit] 이 이미 막는다(1.5초 창 안의 같은 말은 접는다).
     */
    private fun flushPartial(): Boolean {
        val p = partial.trim()
        partial = ""
        if (p.isBlank()) return false
        Log.i(TAG, "최종이 비어 부분결과를 확정으로 쓴다: " + p)
        emit(p)
        return true
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        touch()
        if (!t.isNullOrBlank()) { partial = t; lastVoiceAt = System.currentTimeMillis(); hardFails = 0 }
        Log.i(TAG, "partial=" + t)
    }

    override fun onResults(results: Bundle?) {
        if (!listening) return          // 껐는데 뒤늦게 온 결과를 맥에 쓰지 않는다
        touch()
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        Log.i(TAG, "final=" + t)
        if (!t.isNullOrBlank()) { partial = ""; emit(t.trim()) } else flushPartial()
        listen(50)
    }

    override fun onError(error: Int) {
        Log.w(TAG, "error=$error engine=$engine locale=$locale listening=$listening")
        // 🚨 끈 뒤에 오는 콜백은 «오류»가 아니다.
        //    `stop()` 의 `cancel()` 에 서비스가 `ERROR_CLIENT(5)` 로 답하는데, 그걸 오류로 띄우는 바람에
        //    **받아쓰기가 멀쩡히 됐는데도 «음성 인식 오류»가 떴다**(2026-09-22 사용자 신고).
        if (!listening) return
        touch()
        awaitingReady = false
        if (ignoreNextError) {
            ignoreNextError = false
            // 🚨 **취소에 대한 답은 `ERROR_CLIENT(5)` 뿐이다.**
            //    전에는 «다음에 오는 오류»를 무엇이든 삼켰다. 그래서 한/영 전환 직후 오는
            //    `ERROR_RECOGNIZER_BUSY(8)` 까지 먹었고, BUSY 처리(물러서기·엔진 전환)가
            //    **한 번도 안 돌았다.** 앱은 듣는 줄 알고 가만히 있는다 —
            //    사용자 신고 「한/영 바꾸면 인식이 안 되는 경우가 많다」가 이것이다(2026-09-23 실측:
            //    `retarget → ko-KR` 152ms 뒤 `error=8` 이 «취소 답»으로 삼켜지고 1.7초 공백).
            if (error == SpeechRecognizer.ERROR_CLIENT) {
                Log.i(TAG, "내가 취소한 것에 대한 답이라 무시 error=$error")
                return
            }
            Log.w(TAG, "취소 직후지만 ERROR_CLIENT 가 아니다 — 진짜 오류로 다룬다 error=$error")
        }
        // 「못 알아들었다」로 끝나도 부분결과가 남아 있으면 그건 알아들은 것이다 — 버리지 않는다.
        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) flushPartial()
        when (error) {
            // 말을 잠깐 멈춘 것뿐이다 — 조용히 같은 인스턴스로 다시 건다.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { partial = ""; listen(50) }

            // 🚨 서버(인식 서비스)가 끊겼다. 치명적이 아니라 **인스턴스를 버리고 쉬었다 붙는** 오류다.
            //    한 마디마다 인식기를 새로 만들면 여기서 계속 걸린다(SODA 재연결 레이스).
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> { drop(); listen(700) }
            // 🚨 누군가 인식기를 쥐고 있다. **무한히 다시 걸면 안 된다** —
            //    실제로 남아 있던 계측 테스트 프로세스가 온디바이스 인식기를 물고 있어
            //    0.5초마다 20번 넘게 헛돌았다(2026-09-22). 상한을 두고, 중간에 엔진을 바꿔 본다.
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                busyStreak++
                busyCount++
                runCatching { sr?.cancel() }
                when {
                    // ⚠️ 여기서 인스턴스를 새로 만들면 안 된다 — 그게 BUSY 를 부추긴다.
                    //    같은 인스턴스로 물러섰다 다시 건다.
                    busyStreak < BUSY_TRIES -> listen(400L * busyStreak)
                    busyStreak == BUSY_TRIES && preferOnDevice -> {
                        preferOnDevice = false
                        fellBackAt = System.currentTimeMillis()
                        message = "온디바이스 자리가 차서 기본 엔진으로 간다(잠시 뒤 다시 시도)"
                        drop(); listen(400)
                    }
                    else -> { message = "음성 인식기가 계속 바쁘다 — 껐다"; stop() }
                }
            }

            // 온디바이스에 이 언어가 없다 → 기본 엔진으로 한 번 내려간다.
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                if (engine == DictationEngine.OnDevice) {
                    // 오류가 아니라 «이 언어는 아직 기기에 없다»는 사실이다.
                    // 기억해 두고(다음부터 헛왕복 안 한다), 내려받기를 걸어 두고, 기본 엔진으로 간다.
                    noOnDeviceAt[locale] = System.currentTimeMillis()
                    requestModelDownload(locale)
                    message = "$locale 은 기기에 아직 없어 기본 엔진으로 간다(내려받기 요청함)"
                    drop(); listen(200)
                } else {
                    message = "$locale 를 인식할 수 없다"; stop()
                }
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { message = "마이크 권한이 없다"; stop() }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> hardFail("인식 서버에 닿지 못했다")
            else -> hardFail("음성 인식 오류 ($error)")
        }
    }

    // 테스트가 상한값을 그대로 읽게 internal — 리터럴로 베껴 두면 값이 바뀔 때 조용히 어긋난다.
    internal companion object {
        const val TAG = "Dictation"
        /** 결과 없이 이어진 하드 실패가 이만큼이면 스스로 끈다 — 무한 재시도는 배터리만 먹는다. */
        const val MAX_HARD_FAILS = 5
        /** 빈 분절을 몇 번까지 봐 주나. 넘으면 그 엔진은 분절 세션을 못 하는 것으로 본다. */
        const val MAX_EMPTY_SEGMENTS = 3
        /** 이만큼 말이 없으면 스스로 끈다. */
        const val IDLE_STOP_MS = 120_000L
        /** 이 안에 같은 말이 또 오면 이중 전달로 본다. */
        const val DEDUPE_MS = 1_500L
        /** 연속 BUSY 를 이만큼 만나면 엔진을 바꿔 보고, 그래도 안 되면 끈다. */
        const val BUSY_TRIES = 3
        /** 끈 뒤 이만큼 더 들고 있다가 놓는다. 짧은 재토글은 재사용, 긴 유휴는 자리 반납. */
        const val IDLE_RELEASE_MS = 4_000L
        /** 망으로 내려간 뒤 이만큼 지나면 온디바이스를 다시 노려본다. */
        const val ONDEVICE_RETRY_MS = 60_000L
        /** `startListening` 뒤 이만큼 답이 없으면 «조용히 무시당했다»로 본다. */
        const val READY_TIMEOUT_MS = 2_000L
        /** 콜백이 이만큼 끊기면 «먹통»으로 본다. 정상이면 무음이어도 2초마다 뭐라도 온다. */
        /**
         * 온디바이스를 먼저 노릴지. **true = 온디바이스 우선**(2026-09-23 21차 사용자 결정).
         *
         * 20차에 망 우선으로 바꿨던 이유는 «켤 때마다 먹통 온디바이스에 8초를 버린다»였다.
         * 21차에 먹통의 정체를 갈랐다 — `com.google.android.as` 의 인식 엔진이 안 붙는 서비스 쪽
         * 상태이고, 서비스를 재시작하면 즉시 낫는다. 앱이 고칠 수는 없으니 [NO_AUDIO_MS] 에
         * 알아채고 빠진다. 건강할 때 온디바이스는 **분절 세션**이라 마이크가 안 닫혀
         * 「앞·중간 단어 빠짐」(망의 182ms 틈)이 생기지 않는다.
         */
        const val PREFER_ON_DEVICE = true
        const val WEDGE_TIMEOUT_MS = 8_000L
        /**
         * 온디바이스 `ready` 뒤 첫 RMS 를 기다리는 시간.
         * 실측(2026-09-23, 건강한 상태 3회): 첫 RMS 757~808ms. 망은 101~165ms.
         * 먹통이면 `ready` 만 오고 RMS 가 영영 안 온다 — 그걸 8초가 아니라 이만큼에 가른다.
         */
        const val NO_AUDIO_MS = 2_000L
        /** 먹통 감시 주기. */
        const val ALIVE_CHECK_MS = 2_000L
    }
}
