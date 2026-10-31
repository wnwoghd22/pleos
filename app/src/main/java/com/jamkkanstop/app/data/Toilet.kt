package com.jamkkanstop.app.data

/** 화장실 한 곳. 좌표는 WGS84. */
data class Toilet(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val type: ToiletType,
    /** 원본 개방시간 문자열 (예: "24시간", "09:00~18:00"). 없으면 null. */
    val openHours: String?,
)

enum class ToiletType {
    /** 공중화장실 (공공데이터) */
    PUBLIC,
    /** 고속도로 휴게소 */
    REST_AREA,
    /** 주유소·충전소 */
    GAS_STATION;

    /** 차를 세우기 쉬운 곳인지. 휴게소·주유소는 주차가 사실상 보장된다. */
    val easyToStop: Boolean get() = this != PUBLIC
}
