package kr.joonlab.foldlab.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.joonlab.foldlab.input.MacKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「펼침 키보드로 **출력 가능한 ASCII 를 전부** 낼 수 있는가」를 기계로 센다.
 *
 * 2026-09-23 사용자 신고: 「물음표나 슬래시도 안 된다」.
 * 실제로 `` ` `` 와 `/` 키가 배치에 **없었다** — `MacKey.shifted` 에는 `/`→`?`, `` ` ``→`~`
 * 매핑이 이미 있었는데 **누를 키가 없었다.** 눈으로 세면 이런 게 또 빠진다.
 */
@RunWith(AndroidJUnit4::class)
class KeyCoverageTest {

    /** 그 배치에서 «직접 누르거나 ⇧ 를 곁들여» 낼 수 있는 문자 전부. */
    private fun reachable(rows: List<List<KeyDef>>): Set<Char> {
        val out = mutableSetOf<Char>()
        rows.flatten().forEach { k ->
            val a = k.action
            if (a is KeyAction.Ch) {
                out.add(a.ch)
                out.add(MacKey.shiftChar(a.ch))
            }
        }
        return out
    }

    private val fullRows get() = listOf(
        KeyRows.row1, KeyRows.row2, KeyRows.row3, KeyRows.row4, KeyRows.row5
    )

    @Test
    fun C01_펼침_키보드로_출력가능_ASCII_전부를_낼_수_있다() {
        val got = reachable(fullRows)
        // 0x20(space) ~ 0x7E(~) 전부.
        val want = (' '..'~').toSet()
        val missing = (want - got).sorted()
        assertTrue(
            "낼 수 없는 문자가 있다: ${missing.joinToString(" ")} " +
                    "(총 ${missing.size}자) — 배치에 키를 더해야 한다",
            missing.isEmpty()
        )
    }

    @Test
    fun C02_커버_키보드도_123_레이어까지_합치면_ASCII_전부다() {
        val rows = listOf(
            KeyRows.cRow1, KeyRows.cRow2, KeyRows.cRow3,
            KeyRows.cNum1, KeyRows.cNum2, KeyRows.cNum3,
            KeyRows.cRow4(false), KeyRows.cRow5
        )
        val missing = ((' '..'~').toSet() - reachable(rows)).sorted()
        assertTrue(
            "커버에서 낼 수 없는 문자: ${missing.joinToString(" ")} (총 ${missing.size}자)",
            missing.isEmpty()
        )
    }

    /**
     * 역T자 위 두 칸은 **Home·End** 다 — 아래 ←·→ 와 의미가 짝이 맞는 자리.
     * 전에는 비어 있었고 그 둘을 낼 방법이 아예 없었다(2026-09-23).
     * 자세가 바뀌어도 같이 있어야 한다 — 능력은 세 자세가 같다.
     */
    @Test
    fun C04_역T자_위_두_칸이_Home_End_다() {
        assertEquals(kr.joonlab.foldlab.input.MacKey.HOME,
            (KeyRows.keyHome.action as KeyAction.Vk).code)
        assertEquals(kr.joonlab.foldlab.input.MacKey.END,
            (KeyRows.keyEnd.action as KeyAction.Vk).code)
    }

    /**
     * 4행과 5행의 가중치 합은 **정확히 같아야** 한다 — 오른쪽 역T자 방향키 클러스터와
     * 나란히 놓이는 값이다. 한쪽에만 키를 더하면 클러스터가 어긋나 손이 매번 헤맨다.
     * (`/` 를 더하면서 LEFT_W 를 10.5 → 11.5 로 올리고 space 를 6.5 → 7.5 로 넓혔다.)
     */
    @Test
    fun C03_역T자_정렬이_유지된다() {
        val w4 = KeyRows.row4.sumOf { it.weight.toDouble() }
        val w5 = KeyRows.row5.sumOf { it.weight.toDouble() }
        assertEquals("row4 가 LEFT_W 와 다르다", KeyRows.LEFT_W.toDouble(), w4, 0.001)
        assertEquals("row5 가 LEFT_W 와 다르다", KeyRows.LEFT_W.toDouble(), w5, 0.001)
    }
}
