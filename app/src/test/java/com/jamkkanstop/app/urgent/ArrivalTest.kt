package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.data.Toilet
import com.jamkkanstop.app.data.ToiletType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTest {

    private val restArea = Toilet("r", "서울만남(부산)휴게소", "", 37.459939, 127.042514, ToiletType.REST_AREA, "24시간")

    @Test fun `내비가 알려준 이름이 같으면 도착`() {
        assertTrue(Arrival.isSamePlace("서울만남(부산)휴게소", restArea))
        assertTrue(Arrival.isSamePlace("서울만남 (부산) 휴게소", restArea))
    }

    @Test fun `다른 곳이면 도착이 아니다`() {
        assertFalse(Arrival.isSamePlace("죽전(서울)휴게소", restArea))
        assertFalse(Arrival.isSamePlace("", restArea))
        assertFalse(Arrival.isSamePlace(null, restArea))
    }

    @Test fun `100m 안이면 위치로도 도착`() {
        // 위도 0.0005도 ≈ 55m
        assertTrue(Arrival.isNear(37.460439, 127.042514, restArea, startMeters = 5_000.0))
        // 위도 0.002도 ≈ 220m
        assertFalse(Arrival.isNear(37.461939, 127.042514, restArea, startMeters = 5_000.0))
    }

    @Test fun `처음부터 바로 옆이었으면 위치로는 도착이라고 하지 않는다`() {
        assertFalse(Arrival.isNear(37.459939, 127.042514, restArea, startMeters = 50.0))
    }
}
