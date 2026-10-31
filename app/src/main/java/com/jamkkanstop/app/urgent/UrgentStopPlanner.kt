package com.jamkkanstop.app.urgent

import com.jamkkanstop.app.data.Geo
import com.jamkkanstop.app.data.NearbyToilet
import com.jamkkanstop.app.data.OpenHours
import com.jamkkanstop.app.data.Toilet
import com.jamkkanstop.app.data.ToiletRepository
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
    private val etaQueryCount: Int = 3,
) {
    suspend fun plan(latitude: Double, longitude: Double, now: LocalTime): StopPlan? {
        val nearby = repository.nearest(latitude, longitude, now, limit = 5)
        if (nearby.isEmpty()) return null

        val candidates = nearby.mapIndexed { index, n ->
            val eta = if (index < etaQueryCount) etaSource.etaSeconds(n.toilet) else null
            n.toCandidate(eta)
        }
        val ranked = rank(candidates)
        return StopPlan(best = ranked.first(), backup = ranked.getOrNull(1))
    }

    companion object {
        /** 1분 이내 차이면 같은 것으로 본다. */
        private const val SAME_ETA_SECONDS = 60

        /**
         * 빨리 닿는 순서. 비슷하면(1분 이내) 세우기 쉬운 곳(휴게소·주유소),
         * 그다음 개방시간이 확인된 곳을 앞에 둔다.
         */
        fun rank(candidates: List<StopCandidate>): List<StopCandidate> =
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
