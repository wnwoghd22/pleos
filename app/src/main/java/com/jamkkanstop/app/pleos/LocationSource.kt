package com.jamkkanstop.app.pleos

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LatLng(val latitude: Double, val longitude: Double)

/**
 * 현재 위치. FusedLocation SDK를 먼저 쓰고, 지원하지 않는 차량이거나 권한이 없으면 내비 위치로 대신한다.
 */
class LocationSource(context: Context, private val navi: NaviGateway) {

    private val appContext = context.applicationContext
    private val fused = FusedLocation(appContext)
    private var fusedReady = false

    fun connect() {
        fusedReady = runCatching {
            fused.initialize()
            fused.isSupported()
        }.onFailure { Log.w(TAG, "FusedLocation 초기화 실패", it) }.getOrDefault(false)
    }

    fun release() {
        runCatching { fused.release() }
    }

    suspend fun current(): LatLng? = fromFused() ?: fromNavi()

    /** FusedLocation은 실행 중에 받는 권한(dangerous)이 필요하다. */
    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private suspend fun fromFused(): LatLng? {
        if (!fusedReady || !hasPermission()) return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    requestLastKnown(cont)
                } catch (e: SecurityException) {
                    Log.w(TAG, "FusedLocation 권한 없음", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun requestLastKnown(cont: CancellableContinuation<LatLng?>) {
        fused.getLastKnownLocation(object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                if (cont.isActive) cont.resume(location?.let { LatLng(it.latitude, it.longitude) })
            }

            override fun onError(e: Exception) {
                Log.w(TAG, "FusedLocation 오류", e)
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    private suspend fun fromNavi(): LatLng? =
        navi.currentLocation()?.let { LatLng(it.latitude, it.longitude) }

    companion object {
        private const val TAG = "LocationSource"
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
