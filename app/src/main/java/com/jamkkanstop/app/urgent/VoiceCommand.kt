package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.pleos.ComfortMode

/** 안내 중에 음성으로 할 수 있는 일. */
sealed interface VoiceCommand {
    data class Comfort(val mode: ComfortMode) : VoiceCommand
    /** 예비 후보로 바꾸기 */
    data object Backup : VoiceCommand
    /** 버티게 돕기 끄기 */
    data object ComfortOff : VoiceCommand
    /** 안내 화면 닫기 (내비 경로는 그대로) */
    data object Dismiss : VoiceCommand
    /** "괜찮아" — 아무것도 하지 않는다 */
    data object Nothing : VoiceCommand

    companion object {
        // 증상을 먼저 본다. "아니 배 아파"는 거절이 아니라 복통이다.
        private val warm = listOf("배아파", "배가아파", "아파", "복통", "따뜻", "따듯", "추워", "춥", "열선", "쥐어짜", "꾸르륵")
        private val cool = listOf("식은땀", "땀", "시원", "더워", "덥", "메스꺼", "울렁", "어지러", "통풍")
        private val backup = listOf("예비", "다른데", "다른곳", "다른화장실", "딴데", "바꿔", "거기말고")
        private val comfortOff = listOf("원래대로", "꺼줘", "끄기", "꺼")
        private val dismiss = listOf("닫아", "닫기", "취소", "그만")
        private val nothing = listOf("괜찮", "됐어", "아니", "없어", "필요없")

        /** 인식된 문장을 명령으로. 모르는 말이면 null. */
        fun parse(text: String?): VoiceCommand? {
            val t = text?.replace(Regex("""[\s.,!?~]"""), "") ?: return null
            if (t.isEmpty()) return null
            return when {
                backup.any { it in t } -> Backup
                cool.any { it in t } -> Comfort(ComfortMode.COOL)
                warm.any { it in t } -> Comfort(ComfortMode.WARM)
                comfortOff.any { it in t } -> ComfortOff
                dismiss.any { it in t } -> Dismiss
                nothing.any { it in t } -> Nothing
                else -> null
            }
        }
    }
}
