package com.jamkkanstop.app.data

import android.content.Context
import org.json.JSONArray
import java.time.LocalTime

/** 주변 화장실 후보. */
data class NearbyToilet(
    val toilet: Toilet,
    val straightMeters: Double,
    val openStatus: OpenHours.Status,
)

/**
 * 앱에 내장된 화장실 DB(assets/toilets.json)를 읽고 가까운 곳을 찾는다.
 * 데이터 생성 방법은 tools/convert_toilets.py 참고.
 */
class ToiletRepository(private val toilets: List<Toilet>) {

    /** 지금 이용 가능한 화장실을 가까운 순서로 [limit]개 돌려준다. 닫힌 곳은 제외한다. */
    fun nearest(
        latitude: Double,
        longitude: Double,
        now: LocalTime,
        limit: Int = 5,
        maxDistanceMeters: Double = 20_000.0,
    ): List<NearbyToilet> =
        toilets.asSequence()
            .map { t ->
                NearbyToilet(
                    toilet = t,
                    straightMeters = Geo.distanceMeters(latitude, longitude, t.latitude, t.longitude),
                    openStatus = OpenHours.status(t.openHours, now),
                )
            }
            .filter { it.straightMeters <= maxDistanceMeters }
            .filter { it.openStatus != OpenHours.Status.CLOSED }
            .sortedBy { it.straightMeters }
            .take(limit)
            .toList()

    val size: Int get() = toilets.size

    companion object {
        private const val ASSET_FILE = "toilets.json"

        fun fromAssets(context: Context): ToiletRepository {
            val json = context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return ToiletRepository(parse(json))
        }

        /** JSON 배열: [{"id","name","address","lat","lng","type","open"}, ...] */
        fun parse(json: String): List<Toilet> {
            val array = JSONArray(json)
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Toilet(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    address = o.optString("address", ""),
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lng"),
                    type = runCatching { ToiletType.valueOf(o.optString("type", "PUBLIC")) }
                        .getOrDefault(ToiletType.PUBLIC),
                    openHours = o.optString("open", "").ifBlank { null },
                )
            }
        }
    }
}
