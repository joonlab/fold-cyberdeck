package kr.joonlab.foldlab.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * DECK 와이어 프로토콜 v1 — 맥 쪽 `deckd/Sources/deckd/Protocol.swift` 와 바이트 단위로 같아야 한다.
 *
 *   0..3   매직 "DECK"
 *   4      버전 (1)
 *   5      타입
 *   6..7   예약
 *   8..15  토큰 태그 = SHA256(token) 앞 8바이트
 *
 * 정수는 전부 빅엔디안.
 */
object Proto {
    val MAGIC = byteArrayOf('D'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte())
    const val VERSION: Byte = 1
    const val HEADER = 16

    // 타입
    const val HELLO: Byte = 0x01
    const val WELCOME: Byte = 0x02
    const val BYE: Byte = 0x03
    const val VIDEO: Byte = 0x10
    const val INPUT: Byte = 0x20
    const val PING: Byte = 0x30
    const val PONG: Byte = 0x31
    const val STATS: Byte = 0x40
    const val KEYFRAME_REQ: Byte = 0x50

    /**
     * 그 프레임의 **«없는 조각 번호만»** 다시 달라. 본문: frameId(4) count(2) idx(2)×count
     *
     * 전에는 조각 하나가 없어도 키프레임 전체(150~200KB)를 다시 달라고 했다. 국제 구간
     * (RTT 58ms)에서는 그 재전송도 같이 깨져서 **조일수록 키프레임이 늘어나는 되먹임**이 됐다.
     */
    const val FRAG_NACK: Byte = 0x51

    /** 한 번의 NACK 에 담을 조각 번호 수 상한. 본문이 maxBody 를 넘으면 안 된다. */
    const val NACK_MAX = 128
    const val RESIZE: Byte = 0x60
    const val VIEW: Byte = 0x61
    /** 서버 → 클라: 캡처할 수 있는 디스플레이 목록. 본문은 [DisplayList] 참조. */
    const val DISPLAYS: Byte = 0x62
    /**
     * 클라 → 서버: 이 디스플레이를 보여 달라. 본문: id(u32).
     * 서버는 **캡처 대상만** 바꾼다 — 해상도·배치는 안 건드린다(9/22 사고 이후 원칙).
     */
    const val SELECT_DISPLAY: Byte = 0x63

    // 입력 종류
    const val K_KEY_DOWN: Byte = 1
    const val K_KEY_UP: Byte = 2
    const val K_MOVE_REL: Byte = 3
    const val K_MOVE_ABS: Byte = 4
    const val K_MOUSE_DOWN: Byte = 5
    const val K_MOUSE_UP: Byte = 6
    const val K_SCROLL: Byte = 7
    const val K_TEXT: Byte = 8
    /** 모디파이어를 실은 클릭 — u8 button · u32 flags. **모디파이어가 있을 때만** 쓴다(옛 deckd 는 배치를 버린다). */
    const val K_MOUSE_DOWN_MODS: Byte = 9
    const val K_MOUSE_UP_MODS: Byte = 10

    fun tokenTag(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).copyOf(8)

    fun frame(type: Byte, tag: ByteArray, body: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteBuffer.allocate(HEADER + body.size).order(ByteOrder.BIG_ENDIAN)
        b.put(MAGIC); b.put(VERSION); b.put(type); b.putShort(0)
        b.put(tag); b.put(body)
        return b.array()
    }

    /** 헤더 검증. 통과하면 타입, 아니면 null. */
    fun typeOf(buf: ByteArray, len: Int, tag: ByteArray): Byte? {
        if (len < HEADER) return null
        for (i in 0..3) if (buf[i] != MAGIC[i]) return null
        if (buf[4] != VERSION) return null
        for (i in 0..7) if (buf[8 + i] != tag[i]) return null
        return buf[5]
    }
}

/** macOS CGEventFlags */
object MacFlags {
    const val SHIFT = 0x00020000
    const val CONTROL = 0x00040000
    const val OPTION = 0x00080000
    const val COMMAND = 0x00100000
    const val FN = 0x00800000
}

/** 입력 이벤트를 하나의 INPUT 패킷 본문으로 모은다. 여러 개를 한 번에 보내 패킷 수를 줄인다. */
class InputBatch {
    private val out = java.io.ByteArrayOutputStream()
    private var count = 0

    private fun u16(v: Int) { out.write((v ushr 8) and 0xff); out.write(v and 0xff) }
    private fun u32(v: Int) { for (s in intArrayOf(24, 16, 8, 0)) out.write((v ushr s) and 0xff) }

    fun key(down: Boolean, code: Int, flags: Int) = apply {
        out.write(if (down) Proto.K_KEY_DOWN.toInt() else Proto.K_KEY_UP.toInt())
        u16(code); u32(flags); count++
    }
    fun moveRel(dx: Int, dy: Int) = apply {
        out.write(Proto.K_MOVE_REL.toInt()); u16(dx.coerceIn(-32768, 32767) and 0xffff)
        u16(dy.coerceIn(-32768, 32767) and 0xffff); count++
    }
    fun moveAbs(x: Int, y: Int) = apply {
        out.write(Proto.K_MOVE_ABS.toInt()); u16(x.coerceIn(0, 65535)); u16(y.coerceIn(0, 65535)); count++
    }
    /** 이 배치가 «누른» 버튼 비트마스크 */
    var maskSet = 0; private set
    /** 이 배치가 «뗀» 버튼 비트마스크 */
    var maskClear = 0; private set

    /** [flags] 가 0 이면 옛 인코딩(5/6) 그대로 — 모디파이어 없는 클릭은 옛 deckd 와도 호환된다. */
    fun mouse(down: Boolean, button: Int, flags: Int = 0) = apply {
        if (flags == 0) {
            out.write(if (down) Proto.K_MOUSE_DOWN.toInt() else Proto.K_MOUSE_UP.toInt())
            out.write(button)
        } else {
            out.write(if (down) Proto.K_MOUSE_DOWN_MODS.toInt() else Proto.K_MOUSE_UP_MODS.toInt())
            out.write(button); u32(flags)
        }
        count++
        if (down) { maskSet = maskSet or (1 shl button); maskClear = maskClear and (1 shl button).inv() }
        else      { maskClear = maskClear or (1 shl button); maskSet = maskSet and (1 shl button).inv() }
    }
    fun scroll(dy: Int, dx: Int) = apply {
        out.write(Proto.K_SCROLL.toInt()); u16(dy.coerceIn(-32768, 32767) and 0xffff)
        u16(dx.coerceIn(-32768, 32767) and 0xffff); count++
    }
    fun text(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(Proto.K_TEXT.toInt()); u16(b.size); out.write(b); count++
    }

    fun isEmpty() = count == 0
    fun toBytes(): ByteArray {
        val body = out.toByteArray()
        val b = ByteBuffer.allocate(1 + body.size)
        b.put(count.toByte()); b.put(body)
        return b.array()
    }
}

/**
 * INPUT 골든 — 폰이 인코딩한 배치를 deckd 가 **같은 뜻으로** 푸는가.
 * `run.sh golden` 이 [HEX] 를 `deckd --decode-input` 에 넣어 [DECODED] 와 대조한다.
 * ⇧+Fn 클릭은 서버가 Fn 을 버리는지 보려고 넣었다(폰은 Fn 을 클릭에 싣지 않는다).
 */
object InputGolden {
    const val HEX = "0704006400c8090000100000" + "0a0000100000" + "090000820000" + "0a0000820000" + "0501" + "0601"
    const val DECODED = "abs(100,200) btn↓0⌘ btn↑0⌘ btn↓0⇧ btn↑0⇧ btn↓1 btn↑1"
    fun sample() = InputBatch()
        .moveAbs(100, 200)
        .mouse(true, 0, MacFlags.COMMAND).mouse(false, 0, MacFlags.COMMAND)
        .mouse(true, 0, MacFlags.SHIFT or MacFlags.FN).mouse(false, 0, MacFlags.SHIFT or MacFlags.FN)
        .mouse(true, 1).mouse(false, 1)
}

/** 맥의 디스플레이 하나. [id] 는 CGDirectDisplayID(u32) — 부호 문제를 피하려고 Long 에 담는다. */
data class DisplayInfo(val id: Long, val width: Int, val height: Int, val flags: Int, val name: String) {
    val isBuiltin get() = flags and DisplayList.BUILTIN != 0
    val isMain get() = flags and DisplayList.MAIN != 0
    val isCurrent get() = flags and DisplayList.CURRENT != 0
    /** 칩에 쓸 짧은 이름. 내장은 «내장», 나머지는 모델명 앞부분. */
    val shortLabel: String get() = if (isBuiltin) "내장" else name.take(10)
}

/**
 * DISPLAYS(0x62) 본문.
 * ```
 * u8 count
 * count × { u32 id · u16 width(pt) · u16 height(pt) · u8 flags · u8 nameLen · nameLen × UTF-8 }
 * flags: bit0 내장 · bit1 주 화면 · bit2 지금 보여 주는 화면
 * ```
 * ⚠️ deckd 의 `DisplayList`(Protocol.swift)와 **바이트 단위로 같아야 한다.**
 *    [GOLDEN_HEX] 는 `deckd --print-protocol-golden` 의 출력이고, `run.sh golden` 이 둘을 대조한다.
 */
object DisplayList {
    const val BUILTIN = 1
    const val MAIN = 2
    const val CURRENT = 4

    const val GOLDEN_HEX = "030000000105e803d607174275696c742d696e20526574696e6120446973706c61790000a1b2078004b0000c466c6970416374696f6e3136fffffffe0348020d000deab080ec838120ed9994eba9b4"

    /** 깨진 패킷이면 null. */
    fun decode(buf: ByteArray, off: Int, end: Int): List<DisplayInfo>? {
        if (off >= end || end > buf.size) return null
        var i = off
        fun u8(): Int? = if (i < end) (buf[i++].toInt() and 0xff) else null
        fun u16(): Int? { val a = u8() ?: return null; val b = u8() ?: return null; return (a shl 8) or b }
        fun u32(): Long? { val a = u16() ?: return null; val b = u16() ?: return null; return (a.toLong() shl 16) or b.toLong() }
        val n = u8() ?: return null
        val out = ArrayList<DisplayInfo>(n)
        repeat(n) {
            val id = u32() ?: return null
            val w = u16() ?: return null
            val h = u16() ?: return null
            val f = u8() ?: return null
            val len = u8() ?: return null
            if (i + len > end) return null     // 이름 중간에서 잘린 패킷 — 반쪽 목록은 안 내보낸다
            val name = String(buf, i, len, Charsets.UTF_8); i += len
            out.add(DisplayInfo(id, w, h, f, name))
        }
        return out
    }
}
