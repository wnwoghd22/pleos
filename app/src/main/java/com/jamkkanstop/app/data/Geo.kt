package com.jamkkanstop.app.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 좌표 사이 직선거리(m). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /**
     * 내비 ETA를 못 받았을 때 쓰는 대략적인 소요 시간(초).
     * 도로는 직선보다 약 1.3배 길고, 도심 평균 시속 30km로 가정한다.
     */
    fun roughEtaSeconds(straightMeters: Double): Int =
        (straightMeters * 1.3 / (30_000.0 / 3600.0)).toInt()
}
