package kr.joonlab.foldlab.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **END 칩(= `disconnect()`) 뒤에 «연결 안 됨»이 화면에 도달하는가.**
 *
 * 2026-09-23 결함: `disconnect` 가 statsLoop 까지 취소해서, 마지막으로 내보낸
 * `DeckStats(connected=true)` 가 그대로 굳었다 → 오버레이가 안 뜨고 `last=null` 이라
 * `resume()` 도 못 살려 앱 재시작 외엔 복구가 없었다.
 *
 * 폰 안에 가짜 deckd(127.0.0.1)를 띄워 WELCOME·STATS 로 실제로 붙인 뒤 끊는다.
 */
@RunWith(AndroidJUnit4::class)
class DeckClientEndTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val token = "end-test-token"
    private val tag = Proto.tokenTag(token)
    private val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
    @Volatile private var serving = true
    @Volatile private var stats = mutableListOf<DeckStats>()

    /** HELLO 를 받으면 WELCOME, 이후 0.3초마다 STATS(심박). */
    private val fake = Thread {
        val buf = ByteArray(2048); val pkt = DatagramPacket(buf, buf.size)
        var peer: Pair<InetAddress, Int>? = null
        var lastBeat = 0L
        while (serving) {
            try {
                pkt.setData(buf, 0, buf.size); server.receive(pkt)
                if (Proto.typeOf(buf, pkt.length, tag) == Proto.HELLO) {
                    peer = pkt.address to pkt.port
                    val w = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)
                        .putInt(SID).putShort(64).putShort(48).put(0).array()
                    send(peer, Proto.frame(Proto.WELCOME, tag, w))
                }
            } catch (_: Exception) {}
            val p = peer ?: continue
            if (System.currentTimeMillis() - lastBeat > 300) {
                lastBeat = System.currentTimeMillis()
                send(p, Proto.frame(Proto.STATS, tag, ByteBuffer.allocate(16).putInt(SID).array()))
            }
        }
    }.apply { isDaemon = true; start() }

    private fun send(p: Pair<InetAddress, Int>, b: ByteArray) =
        try { server.send(DatagramPacket(b, b.size, p.first, p.second)) } catch (_: Exception) {}

    private val client = DeckClient(scope, onStats = { synchronized(this) { stats.add(it) } }, onLog = {})

    private fun latest() = synchronized(this) { stats.lastOrNull() }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    @After fun tearDown() {
        serving = false
        client.disconnect()
        scope.cancel()
        server.close()
    }

    @Test
    fun E01_END_뒤에는_연결_안_됨이_도달한다() {
        client.connect("127.0.0.1", server.localPort, token, 64, 48)
        assertTrue("가짜 deckd 에 붙어야 한다", waitFor(5000) { latest()?.connected == true })

        client.disconnect()
        // statsLoop 주기(1초)보다 넉넉히 — 굳어 있으면 여기서도 true 다
        Thread.sleep(2500)
        assertEquals(false, latest()?.connected)
        assertTrue("끊긴 상태에 화면 목록이 남으면 칩이 엉뚱한 id 를 보낸다", latest()!!.displays.isEmpty())
    }

    @Test
    fun E02_pause_는_끊김을_알리지_않고_resume_으로_다시_붙는다() {
        client.connect("127.0.0.1", server.localPort, token, 64, 48)
        assertTrue(waitFor(5000) { latest()?.connected == true })

        // 백그라운드 전환 — 오버레이 깜빡임·자동 재접속 경합을 만들지 않으려고 알리지 않는다
        val before = synchronized(this) { stats.size }
        client.pause()
        Thread.sleep(1500)
        assertEquals(before, synchronized(this) { stats.size })

        client.resume()
        assertTrue("resume 은 last 로 다시 붙는다", waitFor(5000) {
            synchronized(this) { stats.drop(before).any { it.connected } }
        })
    }

    @Test
    fun E03_자동_재접속은_END_뒤에_멈춘다() {
        // 토큰·뷰포트가 있고 끊겨 있으면 자동으로 붙는다 — 단 사용자가 END 로 끊었으면 안 붙는다
        assertTrue(autoConnectAllowed(token = "t", viewportW = 100, connected = false, endedByUser = false))
        assertFalse(autoConnectAllowed(token = "t", viewportW = 100, connected = false, endedByUser = true))
        assertFalse(autoConnectAllowed(token = "t", viewportW = 100, connected = true, endedByUser = false))
        assertFalse(autoConnectAllowed(token = "", viewportW = 100, connected = false, endedByUser = false))
        assertFalse(autoConnectAllowed(token = "t", viewportW = 0, connected = false, endedByUser = false))
    }

    companion object { const val SID = 7 }
}
