package kr.joonlab.foldlab.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Annex-B H.264 액세스 유닛을 받아 Surface 에 그린다.
 *
 * 키프레임이 오기 전에는 시작하지 않는다 — SPS/PPS 없이 configure 하면
 * 에러 없이 «검은 화면»이 되고, 원인을 화면 밖에서 찾게 된다.
 */
class H264Decoder(private val onNeedKeyframe: () -> Unit) {

    /**
     * 🚨 이 클래스는 **두 스레드가 동시에** 부른다 — 그래서 전부 이 락 아래에서만 코덱을 만진다.
     *
     * - `feed` : 수신 스레드(DeckClient.recvLoop)
     * - `attachSurface`·`setSize`·`release` : **메인 스레드**
     *   (`MainActivity` 의 `SurfaceHolder.Callback` — 접거나 펴면 창이 바뀌어 터진다)
     *
     * 락이 없던 동안 실기에서 이렇게 났다(2026-09-23):
     * `IllegalStateException: Invalid to call during stop()` @ `drain` / `codec is released already`.
     * `feed` 의 catch 가 삼켜서 자가복구처럼 보였지만 실체는 **use-after-release** 다.
     *
     * 덤으로 `surfaceDestroyed` 의 계약도 이 락이 지켜 준다 — 콜백이 돌아가기 전에
     * 그 서피스를 더는 안 쓰고 있어야 하는데, 전에는 수신 스레드가 계속 그리고 있었다.
     *
     * 락을 쥐는 최대 시간은 `dequeueInputBuffer` 타임아웃(8ms) + configure(수십 ms) 수준이라
     * 메인 스레드가 그만큼 기다린다. 접기/펴기는 잦지 않으므로 그 값을 치를 만하다.
     */
    private val lock = Any()

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var started = false
    private val info = MediaCodec.BufferInfo()
    @Volatile var framesRendered = 0L; private set
    @Volatile var lastError: String? = null; private set

    fun attachSurface(s: Surface?) = synchronized(lock) {
        if (surface === s) return@synchronized
        surface = s
        restart()
    }

    fun setSize(w: Int, h: Int) = synchronized(lock) {
        if (w == width && h == height) return@synchronized
        width = w; height = h
        restart()
    }

    private fun restart() {
        release()
        if (surface != null && width > 0 && height > 0) onNeedKeyframe()
    }

    /** @param au 하나의 액세스 유닛(Annex-B, 키프레임이면 앞에 SPS/PPS 포함) */
    fun feed(au: ByteArray, isKeyframe: Boolean) = synchronized(lock) {
        val s = surface ?: return@synchronized
        if (width <= 0 || height <= 0) return@synchronized

        if (!started) {
            if (!isKeyframe) { onNeedKeyframe(); return@synchronized }
            val (sps, pps) = extractParameterSets(au)
                ?: run { onNeedKeyframe(); return@synchronized }
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                // 지연을 프레임 단위로 쌓지 않게 — 원격 조작에서는 화질보다 지연이 먼저다.
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(fmt, s, null, 0)
                c.start()
                codec = c
                started = true
                lastError = null
            } catch (e: Exception) {
                lastError = "디코더 시작 실패: ${e.message}"
                Log.e(TAG, lastError!!, e)
                return@synchronized
            }
        }

        val c = codec ?: return@synchronized
        try {
            val idx = c.dequeueInputBuffer(8_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return@synchronized
                buf.clear()
                if (buf.capacity() < au.size) {
                    // 너무 큰 AU 는 버린다 — 넣으면 예외가 아니라 디코더가 통째로 죽는다.
                    c.queueInputBuffer(idx, 0, 0, 0, 0)
                    onNeedKeyframe()
                    return@synchronized
                }
                buf.put(au)
                c.queueInputBuffer(idx, 0, au.size, System.nanoTime() / 1000,
                    if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            drain(c)
        } catch (e: Exception) {
            lastError = "디코딩 오류: ${e.message}"
            Log.e(TAG, lastError!!, e)
            release()
            onNeedKeyframe()
        }
    }

    /**
     * 테스트 전용 이음매 — `drain` 한복판에 «다른 스레드가 끼어드는 순간»을 만든다.
     *
     * 이 경합은 창이 좁아서(실기 17분에 2회) 확률에 기대는 테스트로는 못 잡는다.
     * 초록이 나와도 «안 터진 것»인지 «고쳐진 것»인지 구별이 안 되므로 게이트로 못 쓴다.
     * 그래서 끼어드는 시점을 테스트가 **강제**한다. 운영 경로에서는 null 이라 비용이 없다.
     */
    internal var onDrainEnter: (() -> Unit)? = null

    private fun drain(c: MediaCodec) {
        onDrainEnter?.invoke()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> { c.releaseOutputBuffer(o, true); framesRendered++ }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return
            }
        }
    }

    /** Annex-B 바이트열에서 SPS(타입7)·PPS(타입8) 를 시작코드까지 포함해 꺼낸다. */
    private fun extractParameterSets(au: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        forEachNal(au) { start, end ->
            val hdrLen = if (start >= 4 && au[start - 4] == 0.toByte()) 4 else 3
            when (au[start].toInt() and 0x1f) {
                7 -> if (sps == null) sps = au.copyOfRange(start - hdrLen, end)
                8 -> if (pps == null) pps = au.copyOfRange(start - hdrLen, end)
            }
        }
        val a = sps; val b = pps
        return if (a != null && b != null) a to b else null
    }

    /** 시작코드(3/4바이트)를 찾아 각 NAL 의 [페이로드 시작, 끝) 을 넘겨준다. */
    private inline fun forEachNal(d: ByteArray, body: (Int, Int) -> Unit) {
        var i = 0
        var prevStart = -1
        while (i + 3 <= d.size) {
            val is4 = i + 4 <= d.size && d[i] == 0.toByte() && d[i + 1] == 0.toByte() &&
                    d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte()
            val is3 = !is4 && d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()
            if (is4 || is3) {
                val payload = i + (if (is4) 4 else 3)
                if (prevStart >= 0) body(prevStart, i)
                prevStart = payload
                i = payload
            } else i++
        }
        if (prevStart in 0..d.size) body(prevStart, d.size)
    }

    fun release() = synchronized(lock) {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        started = false
    }

    companion object { private const val TAG = "DeckDecoder" }
}
