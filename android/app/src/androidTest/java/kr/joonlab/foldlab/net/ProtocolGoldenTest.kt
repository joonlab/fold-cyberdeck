package kr.joonlab.foldlab.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **폰의 DISPLAYS 디코더가 deckd 의 인코더와 바이트 단위로 맞는가.**
 *
 * `Protocol.swift` ↔ `Protocol.kt` 는 한쪽만 고치면 «조용히» 어긋난다(README 경고).
 * 그래서 deckd 가 찍은 골든 바이트열([DisplayList.GOLDEN_HEX] = `deckd --print-protocol-golden`)을
 * 여기서 디코드해 표본과 대조한다. 두 값이 같은지는 `deckd/run.sh golden` 이 본다.
 */
@RunWith(AndroidJUnit4::class)
class ProtocolGoldenTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun D01_골든_목록을_그대로_읽는다() {
        val b = hex(DisplayList.GOLDEN_HEX)
        val list = DisplayList.decode(b, 0, b.size)
        assertEquals(
            listOf(
                DisplayInfo(1L, 1512, 982, DisplayList.BUILTIN or DisplayList.MAIN or DisplayList.CURRENT,
                            "Built-in Retina Display"),
                DisplayInfo(0xA1B2L, 1920, 1200, 0, "FlipAction16"),
                // u32 최상위 비트가 선 id — Int 로 읽으면 음수가 된다
                DisplayInfo(0xFFFFFFFEL, 840, 525, 0, "가상 화면"),
            ),
            list
        )
    }

    @Test
    fun D02_헤더_뒤_오프셋에서도_읽는다() {
        val body = hex(DisplayList.GOLDEN_HEX)
        val b = ByteArray(16) + body
        assertEquals(3, DisplayList.decode(b, 16, b.size)?.size)
    }

    @Test
    fun D03_잘린_패킷은_null() {
        val b = hex(DisplayList.GOLDEN_HEX)
        // 마지막 이름 중간에서 자른다 — 반쯤 읽은 목록을 내보내면 칩이 엉뚱한 화면을 가리킨다
        assertNull(DisplayList.decode(b, 0, b.size - 3))
        assertNull(DisplayList.decode(b, 0, 0))
    }

    @Test
    fun D04_현재_화면과_짧은_이름() {
        val b = hex(DisplayList.GOLDEN_HEX)
        val list = DisplayList.decode(b, 0, b.size)!!
        assertTrue(list[0].isCurrent && list[0].isBuiltin && list[0].isMain)
        assertEquals("내장", list[0].shortLabel)
        assertEquals("FlipAction", list[1].shortLabel)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun I01_INPUT_골든을_그대로_인코딩한다() {
        // deckd 가 이 hex 를 [InputGolden.DECODED] 로 푸는지는 `deckd/run.sh golden` 이 본다
        assertEquals(InputGolden.HEX, InputGolden.sample().toBytes().toHex())
    }

    @Test
    fun I02_모디파이어_없는_클릭은_옛_인코딩_그대로() {
        // 옛 deckd 와의 호환 — 평범한 클릭이 새 종류(9/10)로 나가면 옛 서버가 배치를 통째로 버린다
        assertEquals("020500" + "0600", InputBatch().mouse(true, 0).mouse(false, 0).toBytes().toHex())
        assertEquals("020500" + "0600", InputBatch().mouse(true, 0, 0).mouse(false, 0, 0).toBytes().toHex())
    }

    @Test
    fun I03_버튼_마스크는_모디파이어와_무관하다() {
        // PING 의 «눌린 버튼» 대조가 ⌘-클릭에서도 맞아야 한다
        val b = InputBatch().mouse(true, 1, MacFlags.COMMAND)
        assertEquals(1 shl 1, b.maskSet)
        val u = InputBatch().mouse(false, 1, MacFlags.COMMAND)
        assertEquals(1 shl 1, u.maskClear)
    }
}
