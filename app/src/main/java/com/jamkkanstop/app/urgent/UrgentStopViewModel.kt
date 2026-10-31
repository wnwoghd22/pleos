package com.jamkkanstop.app.urgent

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jamkkanstop.app.data.ToiletRepository
import com.jamkkanstop.app.pleos.LocationSource
import com.jamkkanstop.app.pleos.NaviGateway
import com.jamkkanstop.app.pleos.StopInsertMode
import com.jamkkanstop.app.pleos.StopInsertResult
import com.jamkkanstop.app.pleos.VoiceGuide
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

sealed interface UrgentUiState {
    data object Idle : UrgentUiState
    data object Searching : UrgentUiState
    data class Guiding(
        val plan: StopPlan,
        val insert: StopInsertResult,
    ) : UrgentUiState
    data class Failed(val message: String) : UrgentUiState
}

/**
 * "화장실 급해요" 흐름: 현재 위치 → 가장 빨리 닿는 화장실 → 내비 경로에 추가 → 음성 안내.
 */
class UrgentStopViewModel(app: Application) : AndroidViewModel(app) {

    private val navi = NaviGateway(app)
    private val location = LocationSource(app, navi)
    private val voice = VoiceGuide(app)
    private val repository by lazy { ToiletRepository.fromAssets(app) }
    private val planner by lazy { UrgentStopPlanner(repository, navi) }

    private val _state = MutableStateFlow<UrgentUiState>(UrgentUiState.Idle)
    val state: StateFlow<UrgentUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 급한 순간에 앱이 꺼지면 안 된다. 예상 못 한 오류는 안내 문구로 바꾼다. */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "urgent flow failed", e)
        fail("문제가 생겼어요. 내비에서 직접 화장실을 검색해 주세요.")
    }

    init {
        navi.connect()
        location.connect()
        voice.connect()
    }

    fun onUrgent() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(crashGuard) {
            _state.value = UrgentUiState.Searching

            val here = location.current()
            if (here == null) {
                fail("현재 위치를 확인할 수 없어요.")
                return@launch
            }

            val plan = planner.plan(here.latitude, here.longitude, LocalTime.now())
            if (plan == null) {
                fail("근처 20km 안에 지금 열려 있는 화장실을 찾지 못했어요.")
                return@launch
            }

            val insert = navi.insertStop(plan.best.toilet)
            _state.value = UrgentUiState.Guiding(plan, insert)
            voice.say(Announcements.guiding(plan, insert))
        }
    }

    /** 예비 후보로 바꾼다. 바꾼 뒤에는 원래 1순위가 예비가 된다. */
    fun onSwitchToBackup() {
        val current = _state.value as? UrgentUiState.Guiding ?: return
        val backup = current.plan.backup ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch(crashGuard) {
            val insert = navi.replaceStop(current.insert, backup.toilet)
            val plan = StopPlan(best = backup, backup = current.plan.best)
            _state.value = UrgentUiState.Guiding(plan, insert)
            voice.say(Announcements.guiding(plan, insert))
        }
    }

    fun onDismiss() {
        job?.cancel()
        _state.value = UrgentUiState.Idle
    }

    private fun fail(message: String) {
        _state.value = UrgentUiState.Failed(message)
        voice.say(message)
    }

    override fun onCleared() {
        voice.release()
        location.release()
        navi.release()
    }

    private companion object {
        const val TAG = "UrgentStop"
    }
}

/** 음성·화면 문구. 짧고, 서두르지 않게. */
object Announcements {

    fun minutes(etaSeconds: Int): Int = ((etaSeconds + 59) / 60).coerceAtLeast(1)

    fun guiding(plan: StopPlan, insert: StopInsertResult): String {
        val best = plan.best
        val head = "${best.toilet.name}까지 약 ${minutes(best.etaSeconds)}분이에요."
        val route = when {
            !insert.confirmed -> "내비 경로 반영은 확인되지 않았어요."
            insert.mode == StopInsertMode.AS_DESTINATION -> "목적지로 안내할게요."
            insert.mode == StopInsertMode.REBUILT_ROUTE_DROPPED_SECOND -> "경유지로 넣었고, 두 번째 경유지는 뺐어요."
            else -> "원래 목적지는 그대로 두고 경유지로 넣었어요."
        }
        return "$head $route 서두르지 않아도 괜찮아요."
    }
}
