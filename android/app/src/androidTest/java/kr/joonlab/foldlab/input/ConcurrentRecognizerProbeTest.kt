package kr.joonlab.foldlab.input

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **망 엔진이 인식기 «두 개»를 동시에 받아 주는가.**
 *
 * 왜 재나: 지금은 한 마디가 끝날 때마다 세션을 닫았다 연다. 그 사이 마이크가 닫혀 있고
 * (실측 182ms — `MIC_END_OF_DATA` → `first mic audio buffer`), 그때 말한 단어는 통째로 사라진다.
 * 자연스러운 속도로 한 음절쯤 되는 길이라 **짧은 단어가 간혹 빠진다**(2026-09-23 사용자 신고).
 *
 * 갭을 0 으로 만들려면 A 가 마무리하는 동안 B 가 이미 듣고 있어야 한다(이중 버퍼링).
 * 그게 되는지는 **동시 세션을 몇 개 주는가**에 달렸다. 온디바이스는 1개로 실측돼 있다(10차).
 * 망은 안 재 봤다 — 그래서 여기서 잰다. **추측으로 설계하지 않는다.**
 *
 * ⚠️ 이 테스트는 «되는지»를 기록하는 프로브다. 결과가 어느 쪽이든 실패로 두지 않는다 —
 *    기기·계정 상태에 흔들리는 것을 게이트로 쓰면 깨진 채 초록이 된다(10차 교훈).
 *    판정은 남긴 파일을 사람이 읽고 한다.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentRecognizerProbeTest {

    /** 사람이 말을 시작할 때까지 기다리는 시간. 테스트가 도는 동안 말해야 한다. */
    private val SPEAK_WAIT_MS = 30_000L

    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    private class Probe(val name: String, val t0: Long) : RecognitionListener {
        val ready = CountDownLatch(1)
        /** 말이 실제로 잡힌 순간 — 이때가 «A 가 확실히 살아 있는» 시점이다. */
        val spoke = CountDownLatch(1)
        var error: Int? = null
        /** 각 사건의 «몇 ms 에 일어났나» — 순서를 봐야 «동시»인지 «자리가 나서»인지 갈린다. */
        var readyAt = -1L
        var errorAt = -1L
        val log = StringBuilder()
        private fun now() = System.currentTimeMillis() - t0
        override fun onReadyForSpeech(p: Bundle?) {
            readyAt = now(); log.append("$name:ready@${readyAt}ms "); ready.countDown()
        }
        override fun onError(e: Int) {
            error = e; errorAt = now(); log.append("$name:error=$e@${errorAt}ms "); ready.countDown()
        }
        override fun onBeginningOfSpeech() { log.append("$name:begin@${now()}ms "); spoke.countDown() }
        override fun onRmsChanged(r: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        /** B 가 뜬 뒤에도 A 가 계속 결과를 내는가 — 이게 «밀려났는지»를 가른다. */
        var partialsAfterMark = 0
        var mark = Long.MAX_VALUE
        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                log.append("$name:partial@${now()}ms ")
                if (now() > mark) partialsAfterMark++
            }
        }
        override fun onResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    /**
     * [keepMs] 를 주면 무음에서도 오래 버티게 한다.
     * ⚠️ A 가 121ms 만에 NO_MATCH 로 죽어 「동시」인지 「자리가 나서」인지 못 갈랐다 —
     *    A 를 살려 둬야 B 와 겹치는지 볼 수 있다. (엔진이 이 힌트를 무시할 수도 있다)
     */
    private fun intent(keepMs: Int = 0) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (keepMs > 0) {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, keepMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, keepMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, keepMs)
        }
    }

    @Test
    fun R01_망_엔진의_동시_세션_수를_잰다() {
        val t0 = System.currentTimeMillis()
        val a = Probe("A", t0)
        val b = Probe("B", t0)
        var sa: SpeechRecognizer? = null
        var sb: SpeechRecognizer? = null

        inst.runOnMainSync {
            sa = SpeechRecognizer.createSpeechRecognizer(ctx)
            sa!!.setRecognitionListener(a)
            sa!!.startListening(intent())
        }

        // 🚨 **말이 잡힐 때까지 기다린다.** 무음에서는 세션이 50~130ms 만에 끝나 겹침을
        //    만들 수 없어서 판정이 계속 보류로 났다(2026-09-23). 말하는 동안은 A 가 살아 있다.
        //    A 가 무음으로 죽으면 다시 건다 — 사람이 말을 시작할 때까지.
        val deadline = System.currentTimeMillis() + SPEAK_WAIT_MS
        while (a.spoke.count > 0 && System.currentTimeMillis() < deadline) {
            if (a.spoke.await(300, TimeUnit.MILLISECONDS)) break
            if (a.error != null) {          // 무음으로 끝났다 — 다시 건다
                a.error = null
                inst.runOnMainSync { runCatching { sa?.startListening(intent()) } }
            }
        }
        val heardSpeech = a.spoke.count == 0L

        // 🚨 말이 잡히고 **1.2초 더** 둔다 — A 가 실제 내용을 쥐게 해야
        //    「B 가 A 를 밀어냈나」를 가릴 수 있다. 바로 걸면 A 가 89ms 만에 NO_MATCH 로
        //    끝나 «우연히 죽은 것»과 «밀려난 것»이 구별되지 않는다(2026-09-23 1차 시도).
        if (heardSpeech) Thread.sleep(1200)
        a.mark = System.currentTimeMillis() - t0

        // A 가 «말을 잡고 있는 바로 그때» B 를 건다.
        inst.runOnMainSync {
            sb = SpeechRecognizer.createSpeechRecognizer(ctx)
            sb!!.setRecognitionListener(b)
            sb!!.startListening(intent())
        }
        b.ready.await(4, TimeUnit.SECONDS)
        Thread.sleep(2500)   // B 가 뜬 뒤에도 A 가 계속 내는지 지켜본다

        // 🚨 이 판정을 두 번 틀렸다(2026-09-23).
        //    ① `a.errorAt < 0` 을 «살아 있다»로 읽었다 — A 가 콜백을 하나도 못 받았는데 통과했다.
        //    ② 둘 다 ready 를 받았다는 것만 봤다 — B[83~134] A[207~] 처럼 안 겹쳐도 통과했다.
        //    재야 하는 것은 «둘 다 열렸나»가 아니라 **«열려 있던 구간이 겹치나»** 다.
        val inf = Long.MAX_VALUE
        val aEnd = if (a.errorAt >= 0) a.errorAt else inf
        val bEnd = if (b.errorAt >= 0) b.errorAt else inf
        val overlapped = a.readyAt >= 0 && b.readyAt >= 0 &&
                a.readyAt < bEnd && b.readyAt < aEnd
        val verdict = when {
            !heardSpeech ->
                "판정 불가 — 말이 안 잡혔다(무음). 테스트가 도는 동안 말을 해야 한다"
            b.error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "동시 1개 — 말하는 중에 B 를 걸었더니 BUSY(8). 이중 버퍼링 불가"
            b.readyAt < 0 ->
                "동시 1개로 보인다 — B 가 ready 조차 못 받았다(오류도 없이 조용히)"
            overlapped && a.partialsAfterMark > 0 ->
                "동시 2개 가능 — 겹쳤고, **B 가 뜬 뒤에도 A 가 결과를 ${a.partialsAfterMark}번 더 냈다**. " +
                "이중 버퍼링 가능"
            overlapped ->
                "밀려난 것으로 보인다 — 겹치긴 했으나 **B 가 뜬 뒤 A 가 결과를 한 번도 못 냈다**. " +
                "이중 버퍼링을 쓰면 A 가 쥐고 있던 말이 날아간다"
            else ->
                "판정 보류 — 말은 잡혔는데 구간이 안 겹쳤다"
        }
        val line = "A[${a.log.trim()}] B[${b.log.trim()}]\n판정: $verdict\n"

        inst.runOnMainSync {
            runCatching { sa?.destroy() }
            runCatching { sb?.destroy() }
        }
        File(ctx.getExternalFilesDir(null), "concurrent-probe.txt").writeText(line)

        // 실패로 두지 않는다 — 결과를 «남기는» 것이 이 테스트의 일이다.
        assertTrue("프로브가 아무것도 못 남겼다", line.isNotBlank())
    }
}
