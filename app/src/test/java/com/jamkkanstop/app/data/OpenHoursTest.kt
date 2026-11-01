package com.jamkkanstop.app.data

import com.jamkkanstop.app.data.OpenHours.Status
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class OpenHoursTest {

    private fun at(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun `24시간은 항상 열림`() {
        assertEquals(Status.OPEN, OpenHours.status("24시간", at(3)))
        assertEquals(Status.OPEN, OpenHours.status("상시 개방", at(23, 59)))
    }

    @Test fun `시간 범위 안이면 열림, 밖이면 닫힘`() {
        assertEquals(Status.OPEN, OpenHours.status("09:00~18:00", at(9)))
        assertEquals(Status.CLOSED, OpenHours.status("09:00~18:00", at(18)))
        assertEquals(Status.CLOSED, OpenHours.status("09:00 - 18:00", at(7, 30)))
    }

    @Test fun `24시까지면 자정 직전까지 열림`() {
        assertEquals(Status.OPEN, OpenHours.status("05:00~24:00", at(23, 59)))
        assertEquals(Status.CLOSED, OpenHours.status("05:00~24:00", at(4, 59)))
    }

    @Test fun `자정을 넘기는 범위`() {
        assertEquals(Status.OPEN, OpenHours.status("18:00~02:00", at(1)))
        assertEquals(Status.CLOSED, OpenHours.status("18:00~02:00", at(12)))
    }

    @Test fun `실제 데이터에 많은 변형 표기`() {
        assertEquals(Status.OPEN, OpenHours.status("00:00~00:00", at(3)))
        assertEquals(Status.OPEN, OpenHours.status("09:00∼18:00", at(10)))
        assertEquals(Status.CLOSED, OpenHours.status("09~18", at(20)))
        assertEquals(Status.OPEN, OpenHours.status("(평일)09:00~18:00", at(10)))
        assertEquals(Status.OPEN, OpenHours.status("05:00~01:00", at(0, 30)))
    }

    @Test fun `시간이 아닌 숫자 범위는 미확인`() {
        assertEquals(Status.UNKNOWN, OpenHours.status("(평일)0900~1800", at(12)))
        assertEquals(Status.UNKNOWN, OpenHours.status("하절기(7~9월)", at(12)))
    }

    @Test fun `알 수 없는 형식은 미확인`() {
        assertEquals(Status.UNKNOWN, OpenHours.status(null, at(12)))
        assertEquals(Status.UNKNOWN, OpenHours.status("", at(12)))
        assertEquals(Status.UNKNOWN, OpenHours.status("근무시간 내", at(12)))
    }
}
