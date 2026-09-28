package kr.joonlab.foldlab.input

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * 마이크가 «앱에 실제로 열리는가»를 인식기와 **분리해서** 잰다.
 *
 * 인식기가 `onReadyForSpeech` 를 주고도 아무 소리도 못 듣는 일이 있었다(2026-09-22).
 * 그때 「인식기가 이상한가, 마이크가 안 열리는가」를 가르지 못하면 엉뚱한 데를 고치게 된다.
 * `peak` 이 0 이면 **무음으로 채워진 것**(차단·silenced), 프레임이 0 이면 아예 못 읽은 것이다.
 */
@RunWith(AndroidJUnit4::class)
class MicProbeTest {

    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun M01_마이크에서_샘플을_읽을_수_있다() {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
        )
        assertEquals("AudioRecord 초기화 실패", AudioRecord.STATE_INITIALIZED, rec.state)
        rec.startRecording()
        val state = rec.recordingState
        val buf = ShortArray(min)
        var frames = 0
        var peak = 0
        repeat(20) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                frames += n
                for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
            }
            Thread.sleep(50)
        }
        rec.stop(); rec.release()
        File(ctx.getExternalFilesDir(null), "mic-probe.txt")
            .writeText("recordState=$state frames=$frames peak=$peak\n")
        assertEquals("녹음이 시작되지 않았다", AudioRecord.RECORDSTATE_RECORDING, state)
        assertTrue("마이크에서 샘플을 한 개도 못 읽었다", frames > 0)
    }
}
