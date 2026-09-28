package kr.joonlab.foldlab.input

/**
 * 두벌식 한글 조합기.
 *
 * 왜 폰에서 조합하나 — 맥의 IME 상태에 기대면 «한/영이 어느 쪽인지»를 원격에서 알 수 없고,
 * 어긋나는 순간 입력이 통째로 뒤섞인다. 여기서 완성 음절을 만들어 유니코드로 보내면
 * 맥 쪽 IME 설정과 무관하게 항상 같은 결과가 나온다.
 *
 * 조합 중인 글자는 [composing] 으로 화면에 미리 보여주고, 확정되면 [commit] 으로 나간다.
 */
class Hangul {

    private val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    /** 겹모음: (앞, 뒤) → 합쳐진 모음 */
    private val VOWEL_PAIR = mapOf(
        'ㅗ' to mapOf('ㅏ' to 'ㅘ', 'ㅐ' to 'ㅙ', 'ㅣ' to 'ㅚ'),
        'ㅜ' to mapOf('ㅓ' to 'ㅝ', 'ㅔ' to 'ㅞ', 'ㅣ' to 'ㅟ'),
        'ㅡ' to mapOf('ㅣ' to 'ㅢ')
    )
    /** 겹받침: (앞, 뒤) → 합쳐진 받침 */
    private val JONG_PAIR = mapOf(
        'ㄱ' to mapOf('ㅅ' to 'ㄳ'),
        'ㄴ' to mapOf('ㅈ' to 'ㄵ', 'ㅎ' to 'ㄶ'),
        'ㄹ' to mapOf('ㄱ' to 'ㄺ', 'ㅁ' to 'ㄻ', 'ㅂ' to 'ㄼ', 'ㅅ' to 'ㄽ',
                      'ㅌ' to 'ㄾ', 'ㅍ' to 'ㄿ', 'ㅎ' to 'ㅀ'),
        'ㅂ' to mapOf('ㅅ' to 'ㅄ')
    )
    /** 겹받침을 쪼갤 때: 합쳐진 받침 → (남길 받침, 초성으로 넘길 자음) */
    private val JONG_SPLIT = JONG_PAIR.flatMap { (a, m) -> m.map { (b, c) -> c to (a to b) } }.toMap()

    private var cho: Char? = null
    private var jung: Char? = null
    private var jong: Char? = null

    /** 지금 조합 중인 글자(없으면 빈 문자열). */
    val composing: String
        get() {
            val c = cho; val v = jung; val t = jong
            return when {
                c != null && v != null -> buildSyllable(c, v, t).toString()
                c != null -> c.toString()
                v != null -> v.toString()
                else -> ""
            }
        }

    private fun buildSyllable(c: Char, v: Char, t: Char?): Char {
        val ci = CHO.indexOf(c); val vi = JUNG.indexOf(v)
        val ti = if (t == null) 0 else JONG.indexOf(t)
        return (0xAC00 + (ci * 21 + vi) * 28 + ti).toChar()
    }

    private fun flush(): String {
        val s = composing
        cho = null; jung = null; jong = null
        return s
    }

    /**
     * 자모 하나를 넣는다. 확정되어 나가는 문자열을 돌려준다(없으면 "").
     * 조합 중인 글자는 [composing] 으로 따로 본다.
     */
    fun input(jamo: Char): String {
        val isVowel = JUNG.indexOf(jamo) >= 0
        var out = ""

        if (!isVowel) {
            // 자음
            when {
                cho == null && jung == null -> cho = jamo
                cho != null && jung == null -> { out = flush(); cho = jamo }   // ㄱㄴ → 'ㄱ' 확정
                jong == null -> {
                    if (JONG.indexOf(jamo) > 0) jong = jamo
                    else { out = flush(); cho = jamo }                          // 받침이 될 수 없는 자음
                }
                else -> {
                    val merged = JONG_PAIR[jong]?.get(jamo)
                    if (merged != null) jong = merged
                    else { out = flush(); cho = jamo }
                }
            }
        } else {
            // 모음
            when {
                jung == null && cho == null -> jung = jamo                      // 홀로 선 모음
                jung == null -> jung = jamo                                     // 초성 + 중성
                jong == null -> {
                    val merged = VOWEL_PAIR[jung]?.get(jamo)
                    if (merged != null) jung = merged
                    else { out = flush(); jung = jamo }
                }
                else -> {
                    // 받침이 다음 글자의 초성으로 넘어간다 — 「한글」의 ㄴ이 「그」로 가는 그 동작
                    val split = JONG_SPLIT[jong]
                    val moving: Char
                    if (split != null) { jong = split.first; moving = split.second }
                    else { moving = jong!!; jong = null }
                    out = flush()
                    cho = moving; jung = jamo
                }
            }
        }
        return out
    }

    /** 백스페이스. 조합 중이면 한 조각만 지우고 true, 조합이 없으면 false(맥으로 Delete 를 보낼 것). */
    fun backspace(): Boolean {
        when {
            jong != null -> {
                val split = JONG_SPLIT[jong]
                jong = split?.first
            }
            jung != null -> {
                val cur = jung!!
                val undo = VOWEL_PAIR.entries.firstNotNullOfOrNull { (a, m) ->
                    m.entries.firstOrNull { it.value == cur }?.let { a }
                }
                jung = undo
            }
            cho != null -> cho = null
            else -> return false
        }
        return true
    }

    /** 조합 중인 것을 확정해 내보낸다(스페이스·엔터·영문 전환·포커스 이동 시). */
    fun finish(): String = flush()

    fun hasComposing(): Boolean = cho != null || jung != null

    companion object {
        /** 두벌식 자판: 영문 키 → 자모. 대문자(Shift)는 된소리·겹모음. */
        private val LOWER = mapOf(
            'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
            'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
            'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
            'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
            'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ',
            'b' to 'ㅠ', 'n' to 'ㅜ', 'm' to 'ㅡ'
        )
        private val UPPER = mapOf(
            'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ',
            'O' to 'ㅒ', 'P' to 'ㅖ'
        )
        /** 영문 키 문자를 자모로. 한글 자판에 없는 키면 null. */
        fun jamoFor(c: Char): Char? = UPPER[c] ?: LOWER[c.lowercaseChar()]
        /** 키캡에 그릴 글자(한글 모드). 없으면 원래 글자 그대로. */
        fun capFor(c: Char): String = (UPPER[c] ?: LOWER[c.lowercaseChar()])?.toString() ?: c.toString()
    }
}
