package com.jamkkanstop.app.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComfortRulesTest {

    private val seatTemp = Levels(-3, 3) // 음수 냉각, 0 꺼짐, 양수 열선
    private val threeSteps = Levels(0, 3)

    @Test fun `복통 모드는 시트 열선을 중간 단계로`() {
        assertEquals(2, ComfortRules.seatTemperature(ComfortMode.WARM, 0, seatTemp))
        assertEquals(1, ComfortRules.seatTemperature(ComfortMode.WARM, 0, Levels(0, 1)))
        assertNull("열선이 없는 차", ComfortRules.seatTemperature(ComfortMode.WARM, 0, Levels(-3, 0)))
    }

    @Test fun `식은땀 모드는 켜진 열선만 끈다`() {
        assertEquals(0, ComfortRules.seatTemperature(ComfortMode.COOL, 2, seatTemp))
        assertNull(ComfortRules.seatTemperature(ComfortMode.COOL, 0, seatTemp))
        assertNull(ComfortRules.seatTemperature(ComfortMode.COOL, null, seatTemp))
    }

    @Test fun `통풍은 식은땀 모드에서 켜고 복통 모드에서 끈다`() {
        assertEquals(2, ComfortRules.seatVentilation(ComfortMode.COOL, 0, threeSteps))
        assertEquals(0, ComfortRules.seatVentilation(ComfortMode.WARM, 2, threeSteps))
        assertNull(ComfortRules.seatVentilation(ComfortMode.WARM, 0, threeSteps))
    }

    @Test fun `실내 온도는 2도 낮추고 간격에 맞추고 최저를 넘지 않는다`() {
        assertEquals(20.0f, ComfortRules.cabinTemperature(ComfortMode.COOL, 22.0f, 16f, 28f, 0.5f)!!, 0.001f)
        assertEquals(16.0f, ComfortRules.cabinTemperature(ComfortMode.COOL, 17.0f, 16f, 28f, 0.5f)!!, 0.001f)
        assertNull("이미 최저", ComfortRules.cabinTemperature(ComfortMode.COOL, 16.0f, 16f, 28f, 0.5f))
        assertNull("복통 모드는 온도를 안 건드림", ComfortRules.cabinTemperature(ComfortMode.WARM, 22f, 16f, 28f, 0.5f))
    }

    @Test fun `바람은 한 단계만 올리고 최대면 그대로`() {
        assertEquals(3, ComfortRules.fanSpeed(ComfortMode.COOL, 2, Levels(1, 8)))
        assertNull(ComfortRules.fanSpeed(ComfortMode.COOL, 8, Levels(1, 8)))
    }

    @Test fun `조명은 어둡게만 하고 이미 어두우면 그대로`() {
        assertEquals(25, ComfortRules.calmBrightness(80, Levels(0, 100)))
        assertNull(ComfortRules.calmBrightness(10, Levels(0, 100)))
    }
}
