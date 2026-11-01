package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.pleos.ComfortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {

    private val warm = VoiceCommand.Comfort(ComfortMode.WARM)
    private val cool = VoiceCommand.Comfort(ComfortMode.COOL)

    @Test fun `복통 표현`() {
        listOf("배 아파", "배가 너무 아파요", "따뜻하게", "따뜻하게 해줘", "추워", "복통이 심해").forEach {
            assertEquals(it, warm, VoiceCommand.parse(it))
        }
    }

    @Test fun `식은땀 표현`() {
        listOf("식은땀 나", "식은땀이 나요", "시원하게", "더워", "땀나", "속이 메스꺼워").forEach {
            assertEquals(it, cool, VoiceCommand.parse(it))
        }
    }

    @Test fun `거절처럼 시작해도 증상이 있으면 증상을 따른다`() {
        assertEquals(warm, VoiceCommand.parse("아니 배 아파"))
    }

    @Test fun `예비 후보`() {
        listOf("예비로", "다른 데", "다른 곳으로 가줘", "거기 말고").forEach {
            assertEquals(it, VoiceCommand.Backup, VoiceCommand.parse(it))
        }
    }

    @Test fun `끄기와 닫기와 괜찮아`() {
        assertEquals(VoiceCommand.ComfortOff, VoiceCommand.parse("원래대로"))
        assertEquals(VoiceCommand.Dismiss, VoiceCommand.parse("취소"))
        assertEquals(VoiceCommand.Nothing, VoiceCommand.parse("괜찮아"))
        assertEquals(VoiceCommand.Nothing, VoiceCommand.parse("됐어요"))
    }

    @Test fun `모르는 말이나 빈 결과는 null`() {
        assertNull(VoiceCommand.parse(null))
        assertNull(VoiceCommand.parse(""))
        assertNull(VoiceCommand.parse("오늘 날씨 어때"))
    }
}
