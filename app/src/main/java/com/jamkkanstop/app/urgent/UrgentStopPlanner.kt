package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.data.Geo
import com.jamkkanstop.app.data.NearbyToilet
import com.jamkkanstop.app.data.OpenHours
import com.jamkkanstop.app.data.Toilet
import com.jamkkanstop.app.data.ToiletRepository
import com.jamkkanstop.app.data.ToiletType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime

/** 실제 도로 기준 소요 시간(초)을 알려주는 곳. 보통 내비게이션. */
fun interface EtaSource {
    /** 모르면 null. */
    suspend fun etaSeconds(toilet: Toilet): Int?
}

data class StopCandidate(
    val toilet: Toilet,
    val etaSeconds: Int,
    /** true면 내비가 아니라 직선거리로 어림잡은 값. */
    val etaEstimated: Boolean,
    val straightMeters: Double,
    val openStatus: OpenHours.Status,
)

/** 가장 빨리 갈 수 있는 곳 하나 + 예비 후보 하나. */
data class StopPlan(val best: StopCandidate, val backup: StopCandidate?)

/**
 * 급할 때 들를 화장실을 고른다.
 * 직선거리로 후보를 추린 뒤, 상위 몇 곳만 내비 ETA를 물어 실제로 가장 빨리 닿는 곳을 고른다.
 */
class UrgentStopPlanner(
    private val repository: ToiletRepository,
    private val etaSource: EtaSource,
    /** 내비에 실제 시간을 물어볼 후보 수. 내비 응답은 한 곳에 0.1~0.2초라 넉넉히 묻는다. */
    private val etaQueryCount: Int = 7,
) {
    suspend fun plan(latitude: Double, longitude: Double, now: LocalTime): StopPlan? {
        val nearby = withContext(Dispatchers.Default) {
            // 고속도로에서는 직선으로 가까운 곳이 대개 도로 옆 마을이라 출구로 나가야 한다.
            // 그래서 가까운 휴게소·졸음쉼터 몇 곳은 항상 후보에 넣고, 실제 시간은 내비로 비교한다.
            val general = repository.nearest(latitude, longitude, now, limit = GENERAL_CANDIDATES)
            val restAreas = repository.nearest(
                latitude, longitude, now, limit = REST_AREA_CANDIDATES, type = ToiletType.REST_AREA,
            )
            (general + restAreas).distinctBy { it.toilet.id }.sortedBy { it.straightMeters }
        }
        if (nearby.isEmpty()) return null

        // 내비가 한 번 응답하지 않으면 나머지도 기다리지 않고 거리로 추정한다.
        // (내비 앱이 아직 안 떠 있으면 후보마다 몇 초씩 기다리게 되어 급한 사람을 붙잡는다.)
        var naviAnswering = true
        val candidates = nearby.mapIndexed { index, n ->
            val eta = if (naviAnswering && index < etaQueryCount) etaSource.etaSeconds(n.toilet) else null
            if (eta == null) naviAnswering = false
            n.toCandidate(eta)
        }
        val ranked = rank(candidates)
        return StopPlan(best = ranked.first(), backup = ranked.getOrNull(1))
    }

    companion object {
        const val GENERAL_CANDIDATES = 5
        const val REST_AREA_CANDIDATES = 2

        /** 1분 이내 차이면 같은 것으로 본다. */
        private const val SAME_ETA_SECONDS = 60

        /**
         * 빨리 닿는 순서. 비슷하면(1분 이내) 세우기 쉬운 곳(휴게소·주유소),
         * 그다음 개방시간이 확인된 곳을 앞에 둔다.
         */
        /**
         * 내비가 잰 시간과 거리로 추정한 시간은 섞어서 비교하지 않는다.
         * 추정치는 신호·우회를 모르니 늘 짧게 나와서, 더 먼 곳이 이기는 일이 생긴다 (에뮬레이터에서 확인).
         * 잰 시간이 하나라도 있으면 잰 후보끼리 순위를 매기고, 추정 후보는 그 뒤에 둔다.
         */
        fun rank(candidates: List<StopCandidate>): List<StopCandidate> {
            val (measured, estimated) = candidates.partition { !it.etaEstimated }
            return sortByEta(measured) + sortByEta(estimated)
        }

        private fun sortByEta(candidates: List<StopCandidate>): List<StopCandidate> =
            candidates.sortedWith(
                compareBy<StopCandidate> { it.etaSeconds / SAME_ETA_SECONDS }
                    .thenBy { if (it.toilet.type.easyToStop) 0 else 1 }
                    .thenBy { if (it.openStatus == OpenHours.Status.OPEN) 0 else 1 }
                    .thenBy { it.etaSeconds },
            )

        private fun NearbyToilet.toCandidate(eta: Int?) = StopCandidate(
            toilet = toilet,
            etaSeconds = eta ?: Geo.roughEtaSeconds(straightMeters),
            etaEstimated = eta == null,
            straightMeters = straightMeters,
            openStatus = openStatus,
        )
    }
}
