package com.jamkkanstop.app.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.jamkkanstop.app.data.Geo
import com.jamkkanstop.app.data.Toilet
import com.jamkkanstop.app.urgent.EtaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 화장실을 경로에 넣은 방식. 나중에 되돌리거나 바꿀 때 쓴다. */
enum class StopInsertMode {
    /** 안내 중이 아니어서 화장실을 목적지로 설정함 */
    AS_DESTINATION,
    /** 기존 경로에 첫 번째 경유지로 추가함 */
    AS_WAYPOINT,
    /** 경유지가 이미 있어 화장실을 맨 앞에 두고 경로를 다시 짬 */
    REBUILT_ROUTE,
    /** 경유지가 2개(최대)여서 두 번째 경유지를 빼고 경로를 다시 짬 */
    REBUILT_ROUTE_DROPPED_SECOND,
}

data class StopInsertResult(val mode: StopInsertMode, val confirmed: Boolean)

/**
 * Pleos NaviHelper 래퍼.
 * NaviHelper는 조회 결과를 리스너 콜백으로만 주므로, 요청마다 응답을 기다리는 suspend 함수로 감싼다.
 * 콜백에 요청 ID가 없어서 같은 종류의 요청은 [mutex]로 한 번에 하나씩만 보낸다.
 */
class NaviGateway(context: Context) : EtaSource {

    private val navi = NaviHelper(context.applicationContext)
    private val connected = CompletableDeferred<Boolean>()
    private val mutex = Mutex()

    @Volatile private var tripReply: CompletableDeferred<TripInfo?>? = null
    @Volatile private var routeStateReply: CompletableDeferred<RouteStateInfo>? = null
    @Volatile private var waypointsReply: CompletableDeferred<List<WaypointInfo>>? = null
    @Volatile private var destinationReply: CompletableDeferred<DestinationInfo>? = null
    @Volatile private var locationReply: CompletableDeferred<CurrentLocationInfo>? = null
    @Volatile private var routeChangeReply: CompletableDeferred<Boolean>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onTripInfo(tripInfo: TripInfo) {
            Log.d(TAG, "onTripInfo $tripInfo")
            tripReply?.complete(tripInfo)
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            Log.d(TAG, "onRouteStateInfo $routeStateInfo")
            routeStateReply?.complete(routeStateInfo)
        }

        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) {
            Log.d(TAG, "onWayPointInfo $waypointInfo")
            waypointsReply?.complete(waypointInfo)
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(TAG, "onDestinationInfo $destinationInfo")
            destinationReply?.complete(destinationInfo)
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            locationReply?.complete(currentLocationInfo)
        }

        // 새 안내를 시작하면 onRouteStarted가 온다 (에뮬레이터에서 확인). 경로 변경 완료로 본다.
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d(TAG, "onRouteStarted $routeStartInfo")
            routeChangeReply?.complete(true)
        }

        override fun onRoutingCompleted() {
            Log.d(TAG, "onRoutingCompleted")
            routeChangeReply?.complete(true)
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.d(TAG, "onWaypointChanged $waypointChangedInfo")
            routeChangeReply?.complete(true)
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) tripReply?.complete(null)
            else routeChangeReply?.complete(false)
        }
    }

    fun connect() {
        navi.initialize(
            onConnected = {
                navi.addListener(listener)
                connected.complete(true)
            },
            onFailed = { t ->
                Log.w(TAG, "NaviHelper 연결 실패", t)
                connected.complete(false)
            },
        )
    }

    suspend fun isConnected(timeoutMs: Long = 3_000): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.await() } ?: false

    fun release() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    // ---- 조회 ----

    /** 내비가 알고 있는 현재 위치. FusedLocation을 못 쓸 때의 대안. */
    suspend fun currentLocation(): CurrentLocationInfo? = ask<CurrentLocationInfo>(
        set = { locationReply = it },
        clear = { locationReply = null },
    ) { navi.getCurrentLocationInfo() }

    /** 임의 지점까지 실제 도로 기준 소요 시간(초). requestTrip 사용. */
    override suspend fun etaSeconds(toilet: Toilet): Int? {
        val trip = ask<TripInfo?>(
            set = { tripReply = it },
            clear = { tripReply = null },
            timeoutMs = 4_000,
        ) {
            navi.requestTrip(
                TripRequest(
                    latitude = toilet.latitude,
                    longitude = toilet.longitude,
                    poiId = "",
                    poiName = toilet.name,
                    poiSubId = "",
                ),
            )
        } ?: return null
        return normalizeEtaSeconds(trip.eta)
    }

    // ---- 경로 변경 ----

    /**
     * 화장실을 경로에 넣는다. 원래 목적지는 유지한다.
     * - 안내 중이 아니면: 화장실을 목적지로
     * - 경유지가 없으면: 첫 번째 경유지로 추가
     * - 경유지가 있으면: 화장실을 맨 앞에 두고 경로를 다시 요청 (경유지는 최대 2개)
     */
    suspend fun insertStop(toilet: Toilet): StopInsertResult {
        // isRouting은 "경로 계산 중"이라 안내 중에도 false다 (에뮬레이터에서 확인). 안내 여부는 naviMode로 본다.
        val guiding = routeState()?.naviMode in GUIDING_MODES
        if (!guiding) {
            val ok = change { navi.requestRoute(toilet.toRouteInfo()) }
            return StopInsertResult(StopInsertMode.AS_DESTINATION, ok)
        }

        val destination = destination()
        if (destination != null && destination.isNear(toilet)) {
            // 이미 그 화장실로 안내 중이다. 경로를 건드리지 않는다.
            return StopInsertResult(StopInsertMode.AS_DESTINATION, confirmed = true)
        }

        val waypoints = waypoints().orEmpty()
        if (waypoints.isEmpty()) {
            val ok = change { navi.addWaypoint(toilet.toWaypoint(WaypointIndex.FIRST)) }
            return StopInsertResult(StopInsertMode.AS_WAYPOINT, ok)
        }
        if (waypoints.first().isNear(toilet)) {
            return StopInsertResult(StopInsertMode.AS_WAYPOINT, confirmed = true)
        }

        if (destination == null) {
            // 목적지를 못 읽으면 경로를 다시 짤 수 없으니 첫 경유지만 교체한다.
            val ok = change { navi.addWaypoint(toilet.toWaypoint(WaypointIndex.FIRST)) }
            return StopInsertResult(StopInsertMode.AS_WAYPOINT, ok)
        }

        val request = RouteWithWaypointsRequest(
            destination = destination.toRouteInfo(),
            waypoints = listOf(
                toilet.toWaypoint(WaypointIndex.FIRST),
                waypoints.first().toWaypoint(WaypointIndex.SECOND),
            ),
        )
        val ok = change { navi.requestRouteWithWaypoints(request) }
        val mode = if (waypoints.size >= 2) StopInsertMode.REBUILT_ROUTE_DROPPED_SECOND else StopInsertMode.REBUILT_ROUTE
        return StopInsertResult(mode, ok)
    }

    /** [insertStop]으로 넣은 화장실을 다른 곳으로 바꾼다. */
    suspend fun replaceStop(previous: StopInsertResult, toilet: Toilet): StopInsertResult =
        when (previous.mode) {
            StopInsertMode.AS_DESTINATION -> {
                val ok = change { navi.requestRoute(toilet.toRouteInfo()) }
                StopInsertResult(StopInsertMode.AS_DESTINATION, ok)
            }
            else -> {
                change { navi.removeWaypoint(WaypointIndex.FIRST) }
                insertStop(toilet)
            }
        }

    private suspend fun routeState(): RouteStateInfo? = ask<RouteStateInfo>(
        set = { routeStateReply = it },
        clear = { routeStateReply = null },
    ) { navi.getRouteStateInfo() }

    private suspend fun waypoints(): List<WaypointInfo>? = ask<List<WaypointInfo>>(
        set = { waypointsReply = it },
        clear = { waypointsReply = null },
    ) { navi.getWaypointInfo() }

    private suspend fun destination(): DestinationInfo? = ask<DestinationInfo>(
        set = { destinationReply = it },
        clear = { destinationReply = null },
    ) { navi.getDestinationInfo() }

    /** 경로 변경 요청을 보내고 완료/오류 콜백을 기다린다. 응답이 없으면 false. */
    private suspend fun change(call: () -> Unit): Boolean = (ask<Boolean>(
        set = { routeChangeReply = it },
        clear = { routeChangeReply = null },
        // 에뮬레이터에서 경로 재탐색에 8~11초 걸렸다. 여유 있게 기다린다.
        timeoutMs = 20_000,
        call = call,
    ) ?: false).also { Log.d(TAG, "route change confirmed=$it") }

    private suspend fun <T> ask(
        set: (CompletableDeferred<T>) -> Unit,
        clear: () -> Unit,
        timeoutMs: Long = 2_000,
        call: () -> Unit,
    ): T? {
        if (!isConnected()) return null
        return mutex.withLock {
            val reply = CompletableDeferred<T>()
            set(reply)
            try {
                runCatching(call).onFailure { Log.w(TAG, "navi call failed", it) }.getOrNull() ?: return@withLock null
                withTimeoutOrNull(timeoutMs) { reply.await() }
                    .also { Log.d(TAG, "ask done in ${timeoutMs}ms window: ${if (reply.isCompleted) "replied" else "TIMEOUT"}") }
            } finally {
                clear()
            }
        }
    }

    companion object {
        private const val TAG = "NaviGateway"
        private val GUIDING_MODES = setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
        private const val SAME_PLACE_METERS = 50.0

        private fun DestinationInfo.isNear(t: Toilet) =
            Geo.distanceMeters(latitude, longitude, t.latitude, t.longitude) < SAME_PLACE_METERS

        private fun WaypointInfo.isNear(t: Toilet) =
            Geo.distanceMeters(latitude, longitude, t.latitude, t.longitude) < SAME_PLACE_METERS

        /**
         * TripInfo.eta는 남은 시간(초), distance는 미터다 (Pleos Connect 2.1.0 에뮬레이터에서 확인:
         * eta=930 → 내비 화면 약 16~19분). 다른 버전에서 도착 시각(epoch)으로 올 경우에 대비한 처리는 남겨둔다.
         */
        fun normalizeEtaSeconds(raw: Int, nowMillis: Long = System.currentTimeMillis()): Int? = when {
            raw <= 0 -> null
            raw > 1_000_000_000 -> (raw - nowMillis / 1000).toInt().takeIf { it > 0 }
            else -> raw
        }

        private fun Toilet.toRouteInfo() = RouteInfo(
            latitude = latitude,
            longitude = longitude,
            displayLatitude = null,
            displayLongitude = null,
            poiName = name,
            poiId = "",
            address = address,
            poiSubId = "",
        )

        private fun Toilet.toWaypoint(index: WaypointIndex) = RequestWaypointInfo(
            latitude = latitude,
            longitude = longitude,
            poiName = name,
            waypointIndex = index,
            poiId = "",
            address = address,
            poiSubId = "",
        )

        private fun WaypointInfo.toWaypoint(index: WaypointIndex) = RequestWaypointInfo(
            latitude = latitude,
            longitude = longitude,
            poiName = name,
            waypointIndex = index,
            poiId = poiId,
            address = address,
            poiSubId = poiSubId.orEmpty(),
            poiProvider = poiProvider,
        )

        private fun DestinationInfo.toRouteInfo() = RouteInfo(
            latitude = latitude,
            longitude = longitude,
            displayLatitude = displayLatitude,
            displayLongitude = displayLongitude,
            poiName = name,
            poiId = poiId,
            address = address,
            poiSubId = poiSubId.orEmpty(),
            poiProvider = poiProvider,
        )
    }
}
