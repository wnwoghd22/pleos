package com.jamkkanstop.app.pleos

import kotlin.math.roundToInt

/**
 * 기획서 ③ "가는 동안 버티게 돕기"의 두 가지 모드.
 * - WARM(복통 모드): 시트·핸들 열선으로 배와 허리를 따뜻하게
 * - COOL(식은땀 모드): 통풍 시트, 실내 온도 1~2℃ 낮춤, 얼굴 쪽 송풍, 외기 유입
 * 두 모드 모두 엠비언트 라이트를 차분한 색·낮은 밝기로 바꾼다.
 */
enum class ComfortMode { WARM, COOL }

/** 정수 범위(시트 단계, 바람 세기, 밝기 등). 차종마다 다르니 capability에서 받아온다. */
data class Levels(val min: Int, val max: Int) {
    fun clamp(v: Int) = v.coerceIn(min, max)
}

/**
 * 지금 값과 지원 범위를 보고 모드별 목표값을 정한다. 차량 SDK와 분리해서 단위 테스트한다.
 * null은 "건드리지 않음"이다.
 */
object ComfortRules {

    /** 실내 온도는 이만큼 낮춘다 (기획서: 1~2℃). */
    const val COOL_DOWN_CELSIUS = 2f

    /** 차분한 색: 부드러운 하늘색. ARGB, 알파는 0xFF 고정. */
    const val CALM_COLOR: Int = 0xFF4FC3F7.toInt()

    /**
     * 시트 온도 단계. Pleos/AAOS 관례상 0은 꺼짐, 양수는 열선, 음수는 냉각이다.
     * WARM: 열선 중간 단계. COOL: 열선이 켜져 있으면 끈다.
     */
    fun seatTemperature(mode: ComfortMode, current: Int?, levels: Levels): Int? = when (mode) {
        ComfortMode.WARM -> if (levels.max <= 0) null else levels.clamp(maxOf(1, (levels.max + 1) / 2))
        ComfortMode.COOL -> if ((current ?: 0) > 0 && 0 in levels.min..levels.max) 0 else null
    }

    /** 통풍. WARM: 켜져 있으면 끈다. COOL: 중간 단계. */
    fun seatVentilation(mode: ComfortMode, current: Int?, levels: Levels): Int? = when (mode) {
        ComfortMode.WARM -> if ((current ?: levels.min) > levels.min) levels.min else null
        ComfortMode.COOL -> if (levels.max <= levels.min) null else levels.clamp(maxOf(levels.min + 1, (levels.max + 1) / 2))
    }

    /** 핸들 열선. WARM에서만 켠다. */
    fun steeringWheelHeat(mode: ComfortMode, levels: Levels): Int? = when (mode) {
        ComfortMode.WARM -> if (levels.max <= levels.min) null else levels.clamp(maxOf(levels.min + 1, (levels.max + 1) / 2))
        ComfortMode.COOL -> null
    }

    /** 설정 온도(℃). COOL에서만 [COOL_DOWN_CELSIUS]만큼 낮추고, 차량의 온도 간격에 맞춘다. */
    fun cabinTemperature(mode: ComfortMode, current: Float?, min: Float, max: Float, step: Float?): Float? {
        if (mode != ComfortMode.COOL || current == null) return null
        val raw = (current - COOL_DOWN_CELSIUS).coerceIn(min, max)
        val snapped = if (step != null && step > 0f) min + ((raw - min) / step).roundToInt() * step else raw
        val target = snapped.coerceIn(min, max)
        return if (target < current) target else null
    }

    /** 바람 세기. COOL에서 한 단계 올린다. */
    fun fanSpeed(mode: ComfortMode, current: Int?, levels: Levels): Int? {
        if (mode != ComfortMode.COOL || current == null) return null
        val target = levels.clamp(current + 1)
        return if (target > current) target else null
    }

    /** 엠비언트 밝기: 범위의 1/4 정도로 낮춘다. 이미 더 어두우면 그대로 둔다. */
    fun calmBrightness(current: Int?, levels: Levels): Int? {
        val target = levels.clamp(levels.min + (levels.max - levels.min) / 4)
        return if (current == null || current > target) target else null
    }
}
