package kr.joonlab.foldlab.ui

import androidx.compose.ui.graphics.Color

/**
 * 참고한 원격 앱 영상에서 읽어낸 팔레트 (20260922 고해상도 분석 기준).
 * 색을 바꿀 일이 생기면 여기만 고친다.
 */
object DeckColors {
    val bg          = Color(0xFF0D1117)   // 리얼 블랙 베이스
    val panel       = Color(0xFF101216)
    val keycap      = Color(0xFF3B4252)   // 슬레이트 블루 — 일반 키
    val keycapDown  = Color(0xFF4C566A)
    val accent      = Color(0xFFE5A93C)   // 머스타드 옐로우 — ESC·모디파이어·Space
    val accentDown  = Color(0xFFF0C070)
    val mouseBtn    = Color(0xFF5E81AC)   // 마우스 클릭 버튼
    val text        = Color(0xFFECEFF4)
    val textDim     = Color(0xFF8A93A5)
    val trackball   = Color(0xFF232833)
    val trackballRim= Color(0xFF454C5C)
    val ok          = Color(0xFF7FD18B)
    val warn        = Color(0xFFE5A93C)
    val bad         = Color(0xFFE06C75)
}

/** 키 하나의 정의. weight 는 그 행 안에서의 상대 폭. */
data class KeyDef(
    val label: String,
    val weight: Float = 1f,
    val action: KeyAction,
    val accent: Boolean = false
)

sealed interface KeyAction {
    /** 문자 키. 한글 모드에서는 [ch] 를 두벌식으로 옮겨 자모로 쓴다. */
    data class Ch(val ch: Char) : KeyAction
    /** macOS 가상 키코드를 그대로 보낸다. */
    data class Vk(val code: Int) : KeyAction
    /** 끈적이는 모디파이어(한 번 누르면 다음 키에 적용). */
    data class Mod(val flag: Int) : KeyAction
    /** 한/영 전환. */
    data object HanYeong : KeyAction
    /** 숫자·기호 레이어 전환(접힘 커버용 압축 키보드에서만 쓴다). */
    data object NumLayer : KeyAction
    /** 마우스 버튼 (0=좌, 1=우). */
    data class Mouse(val button: Int) : KeyAction
}
