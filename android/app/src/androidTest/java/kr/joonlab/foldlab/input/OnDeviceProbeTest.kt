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

/**
 * **온디바이스 인식기가 실제로 마이크를 여는가** — 앱 로직을 빼고 인식기만 단독으로 잰다.
 *
 * 왜 RMS 인가: 마이크가 열리면 **무음이어도** `onRmsChanged` 가 쏟아진다. 말을 안 해도 갈린다.
 * 20차 증상은 「`ready` 는 오는데 RMS 조차 0」이었다 — 이 프로브는 그 한 가지만 센다.
 *
 * 변형 둘을 나란히 잰다(앱이 쓰는 intent 에 무엇이 붙었는지로 갈리는지 보려고):
 *  - `plain`     : 언어·free-form·부분결과만
 *  - `segmented` : 앱과 같이 분절 세션까지
 *
 * ⚠️ 프로브다. 결과가 어느 쪽이든 실패로 두지 않는다 — 기기 상태(고아 세션 등)에 흔들린다.
 * ⚠️ **끝에 반드시 `cancel` → `destroy`** 로 정리한다. 세션을 연 채 프로세스가 죽으면
 *    `com.google.android.as` 안에 주인 없는 녹음 세션이 남는다(2026-09-23 실측 — 앱을
 *    force-stop 해도 `dumpsys audio` 의 VOICE_RECOGNITION 세션이 `active? true` 로 남았다).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceProbeTest {

    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    private class Count(val t0: Long) : RecognitionListener {
        var readyAt = -1L
        var rms = 0
        var firstRmsAt = -1L
        val events = StringBuilder()
        private fun now() = System.currentTimeMillis() - t0
        override fun onReadyForSpeech(p: Bundle?) { readyAt = now(); events.append("ready@$readyAt ") }
        override fun onRmsChanged(r: Float) { if (rms++ == 0) firstRmsAt = now() }
        override fun onError(e: Int) { events.append("error=$e@${now()} ") }
        override fun onBeginningOfSpeech() { events.append("begin@${now()} ") }
        override fun onEndOfSpeech() { events.append("end@${now()} ") }
        override fun onResults(b: Bundle?) { events.append("final@${now()} ") }
        override fun onSegmentResults(b: Bundle) { events.append("segment@${now()} ") }
        override fun onEndOfSegmentedSession() { events.append("segEnd@${now()} ") }
        override fun onPartialResults(p: Bundle?) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    private fun intent(segmented: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (segmented) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
    }

    private fun measure(name: String, segmented: Boolean, onDevice: Boolean = true): String {
        val t0 = System.currentTimeMillis()
        val c = Count(t0)
        var sr: SpeechRecognizer? = null
        inst.runOnMainSync {
            sr = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                 else SpeechRecognizer.createSpeechRecognizer(ctx)
            sr!!.setRecognitionListener(c)
            sr!!.startListening(intent(segmented))
        }
        Thread.sleep(4_000)
        inst.runOnMainSync {
            runCatching { sr?.cancel() }
            runCatching { sr?.destroy() }
        }
        Thread.sleep(1_000)   // 서비스가 세션을 닫을 틈 — 다음 변형이 자리를 두고 다투지 않게
        return "$name: rms=${c.rms} firstRms@${c.firstRmsAt} [${c.events.toString().trim()}]"
    }

    @Test
    fun P01_온디바이스가_마이크를_여는가() {
        // `network` 는 **도구 검증용 대조군**이다 — 정상 인식기에서 RMS 를 세는지 먼저 보여야
        // 온디바이스의 rms=0 을 «인식기 문제»로 읽을 수 있다(오늘 프로브가 네 번 틀렸다).
        val lines = listOf(measure("network", segmented = false, onDevice = false),
                           measure("plain", segmented = false), measure("segmented", segmented = true))
        val out = lines.joinToString("\n") + "\n"
        android.util.Log.i("OnDeviceProbe", out)
        File(ctx.getExternalFilesDir(null), "ondevice-probe.txt").writeText(out)
        assertTrue(out.isNotBlank())
    }
}
