package kr.joonlab.foldlab.net

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

data class DeckStats(
    val bitrateBps: Long = 0,
    val fps: Int = 0,
    val encodeMs: Int = 0,
    val latencyMs: Int = 0,
    val streamW: Int = 0,
    val streamH: Int = 0,
    val lostFragments: Long = 0,
    val connected: Boolean = false,
    /** 맥이 알려 준 디스플레이 목록(DISPLAYS). 옛 deckd 면 비어 있다. */
    val displays: List<DisplayInfo> = emptyList(),
    val note: String = ""
)

/**
 * 끊겨 있을 때 자동으로 다시 붙을지.
 * 사용자가 END 로 끊었으면 「접속」을 누를 때까지 붙지 않는다 — 안 그러면 0.4초 만에 되붙어 END 가 무의미하다.
 */
fun autoConnectAllowed(token: String, viewportW: Int, connected: Boolean, endedByUser: Boolean): Boolean =
    token.isNotBlank() && viewportW > 0 && !connected && !endedByUser

/**
 * deckd 와 말하는 UDP 클라이언트.
 *
 * 이 랩의 원칙 — «보냈다 ≠ 상대가 들고 있다». 그래서 연결 판정을 «HELLO 를 보냈다» 가 아니라
 * «WELCOME 을 받았다 + 최근 1.5초 안에 뭔가 도착했다» 로 한다.
 */
class DeckClient(
    private val scope: CoroutineScope,
    private val onStats: (DeckStats) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    /**
     * 🚨 송신 전용 스레드.
     * UI 스레드에서 DatagramSocket.send 를 부르면 NetworkOnMainThreadException 이 난다.
     * 이 예외는 **message 가 null** 이라 로그에 `send: null` 로만 남는다 — 원인을 못 찾게 만든다
     * (2026-09-22 실측: 영상은 오는데 입력만 통째로 안 갔다).
     * 큐를 쓰는 또 다른 이유는 **순서 보장**이다. keyDown 과 keyUp 이 뒤집히면 키가 눌린 채 남는다.
     */
    private val sendQueue = LinkedBlockingQueue<ByteArray>(256)
    private var sender: Thread? = null
    private var host: InetAddress? = null
    private var port = 0
    private var tag = ByteArray(8)
    private var jobs = mutableListOf<Job>()

    val decoder = H264Decoder(onNeedKeyframe = { requestKeyframe() })

    @Volatile private var streamW = 0
    @Volatile private var streamH = 0
    @Volatile private var lastRx = 0L
    /** 서버 심박(STATS) 도착 시각. 생존 판정은 이것으로 한다 — PONG 은 세션 없이도 오기 때문이다. */
    @Volatile private var lastStats = 0L
    @Volatile private var sessionId = 0
    @Volatile private var gotWelcome = false
    private val rxBytes = AtomicLong(0)
    private val lostFrames = AtomicLong(0)
    /** NACK 으로 다시 달라고 한 조각 수(진단용) */
    private val nackSent = AtomicLong(0)
    @Volatile private var latencyMs = 0
    @Volatile private var srvBitrate = 0L
    @Volatile private var srvFps = 0
    @Volatile private var srvEncMs = 0
    @Volatile private var displays: List<DisplayInfo> = emptyList()
    @Volatile private var viewportW = 0
    @Volatile private var viewportH = 0

    /** 재조립 중인 프레임. UDP 라 조각이 뒤섞이거나 사라진다 — 오래된 것은 버린다. */
    private class Pending(val count: Int, val key: Boolean) {
        val parts = arrayOfNulls<ByteArray>(count)
        var have = 0
        val bornAt = System.currentTimeMillis()
        /** NACK 을 몇 번 보냈나. 상한을 넘으면 키프레임으로 물러선다. */
        var nacks = 0
        var lastNackAt = 0L
    }
    private val pending = HashMap<Int, Pending>()
    private var lastCompleted = 0
    private var lastKeyRequestFor = 0
    /** 키프레임 전체 재요청을 마지막으로 보낸 시각 — 되먹임(나선)을 끊는 게이트. */
    private var lastKeyReqAt = 0L

    val streamSize: Pair<Int, Int> get() = streamW to streamH

    /** 마지막 접속 파라미터. 포그라운드로 돌아올 때 그대로 다시 붙는다. */
    private var last: Array<Any>? = null

    /**
     * 화면을 벗어나면 통신을 접는다.
     * 🚨 안드로이드는 백그라운드 앱의 소켓 송신을 막는다 — `sendto failed: EPERM`.
     *    안 접으면 실패 로그가 초당 쌓이고 배터리만 먹는다(실측 2026-09-22).
     */
    fun pause() { if (last != null) disconnect(keepLast = true) }

    fun resume() {
        val l = last ?: return
        @Suppress("UNCHECKED_CAST")
        connect(l[0] as String, l[1] as Int, l[2] as String, l[3] as Int, l[4] as Int)
    }

    fun connect(hostStr: String, portNum: Int, token: String, vpW: Int, vpH: Int) {
        disconnect(keepLast = true)
        last = arrayOf(hostStr, portNum, token, vpW, vpH)
        viewportW = vpW; viewportH = vpH
        tag = Proto.tokenTag(token)
        scope.launch(Dispatchers.IO) {
            try {
                host = InetAddress.getByName(hostStr)
                port = portNum
                val s = DatagramSocket()
                s.soTimeout = 500
                // 키프레임은 100~200KB 가 «한 번에» 쏟아진다. 버퍼가 작으면 그 프레임만 통째로 날아가고
                // 델타만 남아 «수신은 되는데 화면은 검은» 상태가 된다. 요청값과 실효값은 다르므로 찍어 본다.
                s.receiveBufferSize = 4 shl 20
                socket = s
                sendQueue.clear()
                startSender()
                Log.i(TAG, "수신버퍼 요청 ${4 shl 20} → 실효 ${s.receiveBufferSize}")
                onLog("→ $hostStr:$portNum 로 HELLO")
                sendHello()
                jobs += scope.launch(Dispatchers.IO) { recvLoop(s) }
                jobs += scope.launch(Dispatchers.IO) { keepAliveLoop() }
                jobs += scope.launch(Dispatchers.Default) { statsLoop() }
            } catch (e: Exception) {
                onLog("접속 실패: ${e.message}")
            }
        }
    }

    fun disconnect(keepLast: Boolean = false) {
        if (!keepLast) last = null
        try { if (gotWelcome) send(Proto.frame(Proto.BYE, tag)) } catch (_: Exception) {}
        jobs.forEach { it.cancel() }; jobs.clear()
        sender?.interrupt(); sender = null
        sendQueue.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        gotWelcome = false
        buttonMask = 0
        sessionId = 0
        displays = emptyList()   // 다른 맥의 화면 목록이 남아 있으면 칩이 엉뚱한 id 를 보낸다
        lastStats = 0
        streamW = 0; streamH = 0
        pending.clear()
        decoder.release()
        // 🚨 statsLoop 도 위에서 같이 취소됐다 — 여기서 알리지 않으면 마지막 `connected=true` 가 굳는다
        //    (END 뒤 오버레이가 안 뜨고 `last=null` 이라 resume 도 못 살리는 막다른 길, 2026-09-23).
        //    pause·connect 의 재접속(keepLast)은 곧 다시 붙으므로 알리지 않는다 — 오버레이가 깜빡이지 않게.
        if (!keepLast) onStats(DeckStats(connected = false))
    }

    fun attachSurface(s: Surface?) = decoder.attachSurface(s)

    fun updateViewport(w: Int, h: Int) {
        if (w == viewportW && h == viewportH) return
        viewportW = w; viewportH = h
        if (!gotWelcome) return
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        b.putShort(w.toShort()); b.putShort(h.toShort())
        send(Proto.frame(Proto.RESIZE, tag, b.array()))
    }

    /** 지금 눌려 있다고 «클라가 믿는» 버튼. PING 에 실어 서버가 대조하게 한다. */
    @Volatile private var buttonMask = 0

    fun sendInput(batch: InputBatch) {
        if (batch.isEmpty()) return
        buttonMask = (buttonMask or batch.maskSet) and batch.maskClear.inv()
        send(Proto.frame(Proto.INPUT, tag, batch.toBytes()))
    }

    /**
     * «맥 화면의 어느 사각형을 보고 있는지»를 서버에 알린다. 값은 디스플레이 대비 0..1 비율.
     * 서버가 그 영역만 잘라 **원본 픽셀로** 보내주므로, 클라에서 확대하는 것과 달리 뭉개지지 않는다.
     */
    fun sendView(x: Float, y: Float, w: Float, h: Float) {
        if (!gotWelcome) return
        fun n(v: Float) = (v.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        b.putShort(n(x).toShort()); b.putShort(n(y).toShort())
        b.putShort(n(w).toShort()); b.putShort(n(h).toShort())
        send(Proto.frame(Proto.VIEW, tag, b.array()))
    }

    fun requestKeyframe() { if (gotWelcome) send(Proto.frame(Proto.KEYFRAME_REQ, tag)) }

    /**
     * 이 맥 화면을 보여 달라. 서버가 캡처를 다시 걸고 WELCOME(새 스트림 크기)과 DISPLAYS 를 다시 준다.
     * 서버는 **자기 목록에 있는 id 만** 받는다 — 여기 든 목록이 낡았으면 무시하고 새 목록을 준다.
     */
    fun selectDisplay(id: Long) {
        if (!gotWelcome) return
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        b.putInt((id and 0xffffffffL).toInt())
        send(Proto.frame(Proto.SELECT_DISPLAY, tag, b.array()))
    }

    // MARK: 내부

    private fun sendHello() {
        val b = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        b.putShort(viewportW.toShort()); b.putShort(viewportH.toShort()); b.putShort(420)
        send(Proto.frame(Proto.HELLO, tag, b.array()))
    }

    private fun send(data: ByteArray) {
        if (!sendQueue.offer(data)) Log.w(TAG, "송신 큐 가득 — 패킷 버림")
    }

    private fun startSender() {
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val data = try { sendQueue.take() } catch (e: InterruptedException) { return@Thread }
                val s = socket ?: continue
                val h = host ?: continue
                try { s.send(DatagramPacket(data, data.size, h, port)) }
                catch (e: Exception) {
                    // 예외 «종류»를 남긴다 — message 가 null 인 예외가 있다
                    Log.w(TAG, "send 실패: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }, "deck-sender")
        t.isDaemon = true
        t.start()
        sender = t
    }

    private suspend fun CoroutineScope.recvLoop(s: DatagramSocket) {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        while (isActive) {
            try {
                pkt.setData(buf, 0, buf.size)
                s.receive(pkt)
            } catch (e: Exception) {
                if (s.isClosed) return
                continue   // soTimeout — 정상
            }
            val len = pkt.length
            val type = Proto.typeOf(buf, len, tag) ?: continue
            lastRx = System.currentTimeMillis()
            rxBytes.addAndGet(len.toLong())
            when (type) {
                Proto.WELCOME -> handleWelcome(buf, len)
                Proto.VIDEO   -> handleVideo(buf, len)
                Proto.STATS   -> handleStats(buf, len)
                Proto.DISPLAYS -> DisplayList.decode(buf, Proto.HEADER, len)?.let { displays = it }
                Proto.PONG    -> {
                    val b = ByteBuffer.wrap(buf, Proto.HEADER, 8).order(ByteOrder.BIG_ENDIAN)
                    latencyMs = (System.currentTimeMillis() - b.long).toInt().coerceIn(0, 9999)
                }
            }
        }
    }

    private fun handleWelcome(buf: ByteArray, len: Int) {
        if (len < Proto.HEADER + 11) return
        val b = ByteBuffer.wrap(buf, Proto.HEADER, 11).order(ByteOrder.BIG_ENDIAN)
        sessionId = b.int
        val w = b.short.toInt() and 0xffff
        val h = b.short.toInt() and 0xffff
        b.get()                     // codec
        gotWelcome = true
        if (w != streamW || h != streamH) {
            streamW = w; streamH = h
            pending.clear()
            decoder.setSize(w, h)
            onLog("WELCOME — 스트림 ${w}x${h}")
        }
    }

    private fun handleStats(buf: ByteArray, len: Int) {
        if (len < Proto.HEADER + 16) return
        val b = ByteBuffer.wrap(buf, Proto.HEADER, 16).order(ByteOrder.BIG_ENDIAN)
        val sid = b.int
        if (sid != sessionId) {
            // 서버가 재시작했거나 다른 세션이다 — 처음부터 다시 붙는다
            gotWelcome = false
            decoder.release()
            pending.clear()
            sendHello()
            return
        }
        lastStats = System.currentTimeMillis()
        srvBitrate = b.int.toLong() and 0xffffffffL
        srvFps = b.short.toInt() and 0xffff
        srvEncMs = b.short.toInt() and 0xffff
    }

    private fun handleVideo(buf: ByteArray, len: Int) {
        if (len < Proto.HEADER + 12) return
        val b = ByteBuffer.wrap(buf, Proto.HEADER, 12).order(ByteOrder.BIG_ENDIAN)
        val fid = b.int
        val idx = b.short.toInt() and 0xffff
        val cnt = b.short.toInt() and 0xffff
        val flags = b.get().toInt()
        b.get()
        val plen = b.short.toInt() and 0xffff
        if (cnt <= 0 || idx >= cnt) return
        val off = Proto.HEADER + 12
        if (off + plen > len) return

        // 이미 지나간 프레임의 늦은 조각은 버린다.
        if (fid <= lastCompleted && lastCompleted - fid < 1000) return

        val p = pending.getOrPut(fid) { Pending(cnt, (flags and 1) != 0) }
        if (p.parts[idx] == null) {
            p.parts[idx] = buf.copyOfRange(off, off + plen)
            p.have++
        }
        // 조각이 빠졌으면 **없는 번호만** 다시 달라고 한다(NACK).
        //
        // 전에는 여기서 키프레임 전체를 다시 요청했다. 국제 구간(RTT 58ms)에서는 그 재전송
        // 150~200KB 도 같이 깨져서, 조일수록 키프레임이 늘어나는 되먹임이 됐다
        // (2026-09-23 실측: 버스트 5Mbps 에서 키프레임 8 → 11개, 유실 19.7%).
        // 우리는 **어느 조각이 없는지 정확히 안다** — 그것만 받으면 왕복 한 번으로 끝난다.
        val now = System.currentTimeMillis()
        if (p.have < p.count && now - p.bornAt > NACK_AFTER_MS &&
            now - p.lastNackAt > NACK_RETRY_MS) {
            if (p.nacks < NACK_MAX_TRIES) {
                p.nacks++
                p.lastNackAt = now
                sendFragNack(fid, p)
            } else if (p.key && fid > lastKeyRequestFor && now - lastKeyReqAt > KEY_REQ_MIN_GAP_MS) {
                // 조각 복구가 세 번 다 실패했다 — 그때만 기준점을 새로 받는다.
                lastKeyRequestFor = fid
                lastKeyReqAt = now
                requestKeyframe()
            }
        }
        if (p.have == p.count) {
            pending.remove(fid)
            lastCompleted = fid
            val total = p.parts.sumOf { it?.size ?: 0 }
            val au = ByteArray(total)
            var o = 0
            for (part in p.parts) { part ?: continue; System.arraycopy(part, 0, au, o, part.size); o += part.size }
            decoder.feed(au, p.key)
        }

        // 조각이 영영 안 오는 프레임을 걷어낸다. 남겨두면 메모리가 샌다.
        if (pending.size > 8) {
            val dead = pending.filterValues { now - it.bornAt > 400 }.keys
            if (dead.isNotEmpty()) {
                lostFrames.addAndGet(dead.size.toLong())
                dead.forEach { pending.remove(it) }
                // ⚠️ 여기도 게이트를 건다. 전에는 무조건 키프레임을 요청해서,
                //    링크가 나쁠수록 키프레임이 쏟아지는 나선이 됐다.
                if (now - lastKeyReqAt > KEY_REQ_MIN_GAP_MS) {
                    lastKeyReqAt = now
                    requestKeyframe()
                }
            }
        }
    }

    /** 「이 프레임의 이 조각들만 다시」 — frameId(4) count(2) idx(2)×count */
    private fun sendFragNack(fid: Int, p: Pending) {
        val missing = ArrayList<Int>(minOf(p.count - p.have, Proto.NACK_MAX))
        for (i in 0 until p.count) {
            if (p.parts[i] == null) {
                missing.add(i)
                if (missing.size >= Proto.NACK_MAX) break
            }
        }
        if (missing.isEmpty()) return
        val b = ByteBuffer.allocate(6 + missing.size * 2).order(ByteOrder.BIG_ENDIAN)
        b.putInt(fid)
        b.putShort(missing.size.toShort())
        missing.forEach { b.putShort(it.toShort()) }
        send(Proto.frame(Proto.FRAG_NACK, tag, b.array()))
        nackSent.addAndGet(missing.size.toLong())
    }

    private suspend fun CoroutineScope.keepAliveLoop() {
        while (isActive) {
            if (!gotWelcome) sendHello()
            // 🚨 버튼 «떼기» 패킷이 UDP 에서 유실되면 맥에서 그 버튼이 영원히 눌린 채 남는다.
            //    (우클릭이 끼면 그 뒤로 모든 게 우클릭처럼 보인다)
            //    그래서 매초 «내가 믿는 버튼 상태»를 같이 보내 서버가 스스로 풀게 한다.
            val b = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            b.putLong(System.currentTimeMillis())
            b.put(buttonMask.toByte())
            send(Proto.frame(Proto.PING, tag, b.array()))
            delay(1000)
        }
    }

    private suspend fun CoroutineScope.statsLoop() {
        while (isActive) {
            delay(1000)
            val rx = rxBytes.getAndSet(0)
            val alive = gotWelcome && (System.currentTimeMillis() - lastStats) < 2500
            onStats(
                DeckStats(
                    bitrateBps = rx * 8,
                    fps = srvFps,
                    encodeMs = srvEncMs,
                    latencyMs = latencyMs,
                    streamW = streamW,
                    streamH = streamH,
                    lostFragments = lostFrames.get(),
                    connected = alive,
                    displays = if (alive) displays else emptyList(),
                    note = decoder.lastError ?: ""
                )
            )
            if (!alive) gotWelcome = false   // 끊기면 다음 keepAlive 가 HELLO 를 다시 보낸다
        }
    }

    companion object {
        private const val TAG = "DeckClient"
        /** 조각이 빠진 걸 보고 NACK 까지 기다리는 시간. 늦게 오는 조각을 헛되이 조르지 않으려고 둔다. */
        private const val NACK_AFTER_MS = 80L
        /** 같은 프레임에 NACK 을 다시 보내기까지. 왕복(홈맥 RTT 58ms)이 한 번은 돌아야 한다. */
        private const val NACK_RETRY_MS = 90L
        /** 조각 복구 시도 상한. 넘으면 키프레임으로 물러선다. */
        private const val NACK_MAX_TRIES = 3
        /** 키프레임 **전체** 재요청의 최소 간격 — 되먹임(나선)을 끊는 게이트.
         *  전에는 게이트가 없어서 링크가 나쁠수록 키프레임이 쏟아졌다. */
        private const val KEY_REQ_MIN_GAP_MS = 1000L
    }
}
