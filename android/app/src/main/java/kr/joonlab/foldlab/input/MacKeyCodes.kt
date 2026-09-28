package kr.joonlab.foldlab.input

/**
 * macOS 가상 키코드 (Carbon `kVK_*`). 물리 키 위치를 가리키는 값이라
 * 맥 쪽 키보드 레이아웃이 무엇이든 «그 자리의 키»가 눌린다.
 */
object MacKey {
    const val A = 0x00; const val S = 0x01; const val D = 0x02; const val F = 0x03
    const val H = 0x04; const val G = 0x05; const val Z = 0x06; const val X = 0x07
    const val C = 0x08; const val V = 0x09; const val B = 0x0B; const val Q = 0x0C
    const val W = 0x0D; const val E = 0x0E; const val R = 0x0F; const val Y = 0x10
    const val T = 0x11; const val O = 0x1F; const val U = 0x20; const val I = 0x22
    const val P = 0x23; const val L = 0x25; const val J = 0x26; const val K = 0x28
    const val N = 0x2D; const val M = 0x2E

    const val N1 = 0x12; const val N2 = 0x13; const val N3 = 0x14; const val N4 = 0x15
    const val N6 = 0x16; const val N5 = 0x17; const val N9 = 0x19; const val N7 = 0x1A
    const val N8 = 0x1C; const val N0 = 0x1D

    const val EQUAL = 0x18; const val MINUS = 0x1B
    const val RBRACKET = 0x1E; const val LBRACKET = 0x21
    const val QUOTE = 0x27; const val SEMICOLON = 0x29; const val BACKSLASH = 0x2A
    const val COMMA = 0x2B; const val SLASH = 0x2C; const val PERIOD = 0x2F
    const val GRAVE = 0x32

    const val RETURN = 0x24; const val TAB = 0x30; const val SPACE = 0x31
    const val DELETE = 0x33; const val ESCAPE = 0x35
    const val FORWARD_DELETE = 0x75
    const val HOME = 0x73; const val END = 0x77
    const val PAGE_UP = 0x74; const val PAGE_DOWN = 0x79

    const val LEFT = 0x7B; const val RIGHT = 0x7C; const val DOWN = 0x7D; const val UP = 0x7E

    const val F1 = 0x7A; const val F2 = 0x78; const val F3 = 0x63; const val F4 = 0x76
    const val F5 = 0x60; const val F6 = 0x61; const val F7 = 0x62; const val F8 = 0x64
    const val F9 = 0x65; const val F10 = 0x6D; const val F11 = 0x67; const val F12 = 0x6F

    /** 영문 소문자·숫자·기호 → (키코드, shift 필요 여부) */
    private val map: Map<Char, Pair<Int, Boolean>> = buildMap {
        val plain = mapOf(
            'a' to A, 'b' to B, 'c' to C, 'd' to D, 'e' to E, 'f' to F, 'g' to G,
            'h' to H, 'i' to I, 'j' to J, 'k' to K, 'l' to L, 'm' to M, 'n' to N,
            'o' to O, 'p' to P, 'q' to Q, 'r' to R, 's' to S, 't' to T, 'u' to U,
            'v' to V, 'w' to W, 'x' to X, 'y' to Y, 'z' to Z,
            '1' to N1, '2' to N2, '3' to N3, '4' to N4, '5' to N5,
            '6' to N6, '7' to N7, '8' to N8, '9' to N9, '0' to N0,
            '-' to MINUS, '=' to EQUAL, '[' to LBRACKET, ']' to RBRACKET,
            '\\' to BACKSLASH, ';' to SEMICOLON, '\'' to QUOTE, ',' to COMMA,
            '.' to PERIOD, '/' to SLASH, '`' to GRAVE, ' ' to SPACE
        )
        plain.forEach { (c, k) -> put(c, k to false) }
        val shifted = mapOf(
            '!' to N1, '@' to N2, '#' to N3, '$' to N4, '%' to N5, '^' to N6,
            '&' to N7, '*' to N8, '(' to N9, ')' to N0,
            '_' to MINUS, '+' to EQUAL, '{' to LBRACKET, '}' to RBRACKET,
            '|' to BACKSLASH, ':' to SEMICOLON, '"' to QUOTE, '<' to COMMA,
            '>' to PERIOD, '?' to SLASH, '~' to GRAVE
        )
        shifted.forEach { (c, k) -> put(c, k to true) }
        ('A'..'Z').forEach { put(it, plain[it.lowercaseChar()]!! to true) }
    }

    fun forChar(c: Char): Pair<Int, Boolean>? = map[c]

    /** Shift 를 누른 상태에서 그 키가 내는 «문자». 유니코드로 직접 보낼 때 쓴다. */
    private val shifted: Map<Char, Char> = mapOf(
        '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')',
        '-' to '_', '=' to '+', '[' to '{', ']' to '}', '\\' to '|',
        ';' to ':', '\'' to '"', ',' to '<', '.' to '>', '/' to '?', '`' to '~'
    )
    fun shiftChar(c: Char): Char = shifted[c] ?: c.uppercaseChar()
}
