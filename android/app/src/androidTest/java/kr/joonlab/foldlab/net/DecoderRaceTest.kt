package kr.joonlab.foldlab.net

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 디코더를 **두 스레드가 동시에** 건드려도 코덱 상태를 깨지 않는지 잰다.
 *
 * 실기에서 나온 증상(2026-09-23 01:16, 스택 트레이스 확보):
 * ```
 * IllegalStateException: Invalid to call during stop(); only valid in executing state
 *   at H264Decoder.drain(H264Decoder.kt:98)      ← 수신 스레드
 *   at H264Decoder.feed(H264Decoder.kt:87)
 *   at DeckClient.handleVideo(DeckClient.kt:304)
 * ```
 * 폰을 접거나 펴면 창이 바뀌어 `surfaceChanged` 가 **메인 스레드에서** 터지고
 * (`MainActivity` 의 `SurfaceHolder.Callback` → `attachSurface`),
 * 그때 수신 스레드는 아직 `drain()` 안에 있다.
 *
 * 지금은 `feed` 의 `catch` 가 삼켜서 «자가복구»처럼 보이지만, 실체는
 * **이미 release 한 코덱을 다른 스레드가 쓰는 것**이다. 예외로 끝나는 건 운이고,
 * 네이티브 크래시로 가는 경로다. 그래서 잰다.
 *
 * ⚠️ 가짜 코덱으로는 이 성질을 못 잰다 — 「락을 잡았나」를 잴 뿐이다.
 * MediaCodec 의 실제 상태기계가 있어야 증상이 나온다.
 */
@RunWith(AndroidJUnit4::class)
class DecoderRaceTest {

    private val w = 320
    private val h = 240

    /**
     * 층 가르기 — 동시성을 빼고 «만든 AU 로 디코더가 시작이나 되는가»만 본다.
     * 이게 빨가면 R01 의 초록은 경합을 안 잰 것이다.
     */
    @Test
    fun R00_만든_키프레임_하나로_디코더가_실제로_그린다() {
        val au = encodeKeyframe()
        val st = SurfaceTexture(0).apply { setDefaultBufferSize(w, h) }
        val surface = Surface(st)
        val dec = H264Decoder(onNeedKeyframe = {})
        dec.attachSurface(surface)
        dec.setSize(w, h)

        // 한 장으로는 출력이 안 나올 수 있으니 같은 키프레임을 여러 번 먹인다.
        repeat(30) { dec.feed(au, true); Thread.sleep(10) }
        val rendered = dec.framesRendered
        val err = dec.lastError
        dec.release(); surface.release(); st.release()

        assertTrue(
            "AU 가 디코더를 못 돌렸다 (rendered=$rendered err=$err) 앞머리=${hex(au, 24)}",
            rendered > 0
        )
    }

    private fun hex(b: ByteArray, n: Int) =
        b.take(n).joinToString(" ") { "%02x".format(it) }

    /**
     * ★ 본 게이트 — 수신 스레드가 `drain()` **안에 있는 동안** 메인 스레드가
     * 서피스를 떼면(= `codec.stop()`) 코덱 상태를 깨지 않아야 한다.
     *
     * 끼어드는 시점을 `onDrainEnter` 이음매로 **강제**한다. 확률에 맡기면
     * (8초·123회 전환으로 실측) 한 번도 안 터져서 초록이 아무것도 증명하지 못했다.
     */
    @Test
    fun R01_drain_한복판에_서피스를_떼도_코덱_상태를_안_깬다() {
        val au = encodeKeyframe()
        assertNotNull("인코더가 키프레임을 못 만들었다", au)

        val st = SurfaceTexture(0).apply { setDefaultBufferSize(w, h) }
        val surface = Surface(st)
        val dec = H264Decoder(onNeedKeyframe = {})

        val inside = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val armed = AtomicBoolean(true)
        // drain 에 처음 들어온 순간 멈춰 세운다 — 그 사이 메인 스레드가 코덱을 멈춘다.
        dec.onDrainEnter = {
            if (armed.compareAndSet(true, false)) {
                inside.countDown()
                resume.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }

        dec.attachSurface(surface)
        dec.setSize(w, h)

        val stop = AtomicBoolean(false)
        val fed = java.util.concurrent.atomic.AtomicLong(0)
        val feeder = thread(name = "feeder") {
            while (!stop.get()) {
                dec.feed(au, true)
                fed.incrementAndGet()
            }
        }

        // 수신 스레드가 drain 안에 들어올 때까지 기다린다.
        val got = inside.await(10, java.util.concurrent.TimeUnit.SECONDS)
        // ★ 여기 못 오면 경합 창이 안 열린 것이다 — 초록이어도 가짜다.
        assertTrue("수신 스레드가 drain 에 한 번도 안 들어왔다 (먹인 횟수=${fed.get()})", got)

        // 접기/펴기로 surfaceChanged 가 터진 것과 같다 (MainActivity 의 SurfaceHolder.Callback).
        // 고쳐져 있으면 여기서 feed 가 끝날 때까지 막힌다 — 그게 정상이다.
        dec.attachSurface(null)
        resume.countDown()

        stop.set(true)
        feeder.join(5000)

        val err = dec.lastError
        dec.release()
        surface.release()
        st.release()

        // 고쳐져 있으면 attachSurface 가 feed 가 끝날 때까지 기다리므로
        // 이 구간에서 오류가 **하나도** 없어야 한다. 문구로 거르지 않는다 —
        // stop() 이냐 released state 냐는 타이밍에 따라 달라지고, 둘 다 같은 결함이다.
        assertNull("drain 중에 코덱을 멈춰 상태가 깨졌다 (먹인 횟수=${fed.get()})", err)
    }

    /**
     * 진짜 H.264 키프레임을 만든다 — SPS/PPS 를 앞에 붙인 Annex-B 액세스 유닛.
     *
     * `H264Decoder` 는 SPS/PPS 가 **AU 안에** 없으면 configure 를 안 한다(검은 화면 방지).
     * 그래서 CODEC_CONFIG 버퍼를 따로 받아 첫 프레임 앞에 이어 붙인다.
     */
    private fun encodeKeyframe(): ByteArray {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)   // 전부 I 프레임
        }
        val enc = MediaCodec.createEncoderByType(mime)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface()
        enc.start()

        repeat(4) { i ->
            val c = input.lockHardwareCanvas()
            c.drawColor(if (i % 2 == 0) Color.RED else Color.BLUE)
            input.unlockCanvasAndPost(c)
            Thread.sleep(30)
        }
        enc.signalEndOfInputStream()

        var csd = ByteArray(0)
        var key: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (key == null && SystemClock.uptimeMillis() < deadline) {
            val o = enc.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val b = enc.getOutputBuffer(o)!!
                val data = ByteArray(info.size)
                b.position(info.offset)
                b.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = data
                else if (data.isNotEmpty()) key = csd + data
                enc.releaseOutputBuffer(o, false)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        enc.stop()
        enc.release()
        input.release()
        return requireNotNull(key) { "인코더가 키프레임을 안 줬다" }
    }
}
