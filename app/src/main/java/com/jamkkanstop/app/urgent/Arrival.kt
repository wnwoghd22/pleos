package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.data.Geo
import com.jamkkanstop.app.data.Toilet

/**
 * 화장실에 도착했는지 판단한다.
 *
 * 1순위는 내비의 도착 이벤트(이름이 같으면 도착)다. 다만 내비 앱 구현에 따라 이벤트가 오지 않을 수 있어서
 * 위치로도 본다. 처음부터 화장실 바로 옆에서 버튼을 누른 경우에는 위치만으로 "도착"이라고 하지 않는다.
 */
object Arrival {

    /** 이 안에 들어오면 도착. 큰 휴게소는 주차장이 넓어 화장실 좌표와 차 위치가 꽤 떨어진다. */
    const val NEAR_METERS = 100.0

    /** 출발할 때 이보다 가까웠으면 위치로는 도착 판단을 하지 않는다 (누르자마자 "도착했어요"를 막음). */
    const val MIN_START_METERS = 200.0

    /** 내비가 알려준 도착 장소 이름이 이 화장실인지. 내비가 이름 앞뒤를 다듬어 줄 수 있어 포함 관계로 본다. */
    fun isSamePlace(arrivedName: String?, toilet: Toilet): Boolean {
        val a = normalize(arrivedName)
        val t = normalize(toilet.name)
        if (a.isEmpty() || t.isEmpty()) return false
        return a == t || a.contains(t) || t.contains(a)
    }

    fun isNear(latitude: Double, longitude: Double, toilet: Toilet, startMeters: Double): Boolean {
        if (startMeters < MIN_START_METERS) return false
        return Geo.distanceMeters(latitude, longitude, toilet.latitude, toilet.longitude) <= NEAR_METERS
    }

    private fun normalize(s: String?) = s.orEmpty().replace(Regex("""[\s()·]"""), "")
}
