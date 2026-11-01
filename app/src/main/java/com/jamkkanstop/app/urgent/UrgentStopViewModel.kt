package com.jamkkanstop.app.urgent

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jamkkanstop.app.data.ToiletRepository
import com.jamkkanstop.app.pleos.ComfortChange
import com.jamkkanstop.app.pleos.ComfortController
import com.jamkkanstop.app.pleos.ComfortMode
import com.jamkkanstop.app.pleos.ComfortRules
import com.jamkkanstop.app.pleos.LocationSource
import com.jamkkanstop.app.pleos.NaviGateway
import com.jamkkanstop.app.pleos.StopInsertMode
import com.jamkkanstop.app.pleos.StopInsertResult
import com.jamkkanstop.app.pleos.VoiceGuide
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
        val comfort: ComfortStatus = ComfortStatus.Offer,
    ) : UrgentUiState
    data class Failed(val message: String) : UrgentUiState
}

/** 가는 동안 버티게 돕기(시트·공조·조명) 상태. */
sealed interface ComfortStatus {
    /** 아직 고르지 않음. 따뜻하게/시원하게 버튼을 보여준다. */
    data object Offer : ComfortStatus
    data class Applying(val mode: ComfortMode) : ComfortStatus
    data class Active(val mode: ComfortMode, val changes: Set<ComfortChange>) : ComfortStatus
}

/**
 * "화장실 급해요" 흐름: 현재 위치 → 가장 빨리 닿는 화장실 → 내비 경로에 추가 → 음성 안내
 * → (고르면) 시트·공조·조명으로 가는 동안 버티게 돕기.
 */
class UrgentStopViewModel(app: Application) : AndroidViewModel(app) {

    private val navi = NaviGateway(app)
    private val location = LocationSource(app, navi)
    private val voice = VoiceGuide(app)
    private val comfort = ComfortController(app)
    private var comfortJob: Job? = null
    // 전국 데이터(약 2만 3천 곳)는 읽는 데 시간이 걸린다. 버튼을 누르기 전에 미리 읽어둔다.
    private val planner = viewModelScope.async(Dispatchers.IO) {
        UrgentStopPlanner(ToiletRepository.fromAssets(app), navi)
    }

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
        comfort.connect()
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

            val plan = planner.await().plan(here.latitude, here.longitude, LocalTime.now())
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
            // 버티게 돕기 상태는 그대로 이어간다.
            val comfortNow = (_state.value as? UrgentUiState.Guiding)?.comfort ?: current.comfort
            _state.value = UrgentUiState.Guiding(plan, insert, comfortNow)
            voice.say(Announcements.guiding(plan, insert, askComfort = comfortNow == ComfortStatus.Offer))
        }
    }

    /** 복통 모드(따뜻하게) / 식은땀 모드(시원하게). */
    fun onComfort(mode: ComfortMode) {
        if (_state.value !is UrgentUiState.Guiding) return
        comfortJob?.cancel()
        comfortJob = viewModelScope.launch(crashGuard) {
            updateComfort(ComfortStatus.Applying(mode))
            val changes = comfort.apply(mode)
            updateComfort(ComfortStatus.Active(mode, changes))
            val minutes = (_state.value as? UrgentUiState.Guiding)?.plan?.best?.etaSeconds?.let(Announcements::minutes)
            voice.say(Announcements.comfort(mode, changes, minutes))
        }
    }

    fun onComfortOff() {
        comfortJob?.cancel()
        comfort.restore()
        updateComfort(ComfortStatus.Offer)
    }

    fun onDismiss() {
        job?.cancel()
        comfortJob?.cancel()
        comfort.restore()
        _state.value = UrgentUiState.Idle
    }

    private fun updateComfort(status: ComfortStatus) {
        val s = _state.value as? UrgentUiState.Guiding ?: return
        _state.value = s.copy(comfort = status)
    }

    private fun fail(message: String) {
        _state.value = UrgentUiState.Failed(message)
        voice.say(message)
    }

    override fun onCleared() {
        comfort.release() // 바꾼 시트·공조·조명을 되돌리고 연결 해제
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

    fun guiding(plan: StopPlan, insert: StopInsertResult, askComfort: Boolean = true): String {
        val best = plan.best
        val head = "${best.toilet.name}까지 약 ${minutes(best.etaSeconds)}분이에요."
        val route = when {
            !insert.confirmed -> "내비 경로 반영은 확인되지 않았어요."
            insert.mode == StopInsertMode.AS_DESTINATION -> "목적지로 안내할게요."
            insert.mode == StopInsertMode.REBUILT_ROUTE_DROPPED_SECOND -> "경유지로 넣었고, 두 번째 경유지는 뺐어요."
            else -> "원래 목적지는 그대로 두고 경유지로 넣었어요."
        }
        val ask = if (askComfort) " 배가 아프면 따뜻하게, 식은땀이 나면 시원하게를 눌러 주세요." else ""
        return "$head $route 서두르지 않아도 괜찮아요.$ask"
    }

    /** 버티게 돕기를 켠 뒤. 무엇을 바꿨는지 짧게 말하고, 호흡 안내로 마무리한다. */
    fun comfort(mode: ComfortMode, changes: Set<ComfortChange>, minutesLeft: Int?): String {
        val what = comfortSummary(mode, changes)
        val head = if (what == null) "이 차에서는 바꿀 수 있는 설정이 없었어요." else "$what."
        val calm = minutesLeft?.let { " ${it}분이면 도착해요." } ?: ""
        return "$head$calm 4초 들이쉬고, 6초 천천히 내쉬어 보세요."
    }

    /** 화면·음성용 요약. 바꾼 것이 없으면 null. */
    fun comfortSummary(mode: ComfortMode, changes: Set<ComfortChange>): String? {
        val parts = buildList {
            when (mode) {
                ComfortMode.WARM -> {
                    if (ComfortChange.SEAT_HEAT in changes) add("시트 열선")
                    if (ComfortChange.WHEEL_HEAT in changes) add("핸들 열선")
                }
                ComfortMode.COOL -> {
                    if (ComfortChange.SEAT_VENT in changes) add("통풍 시트")
                    if (ComfortChange.CABIN_TEMP in changes) add("실내 온도 ${ComfortRules.COOL_DOWN_CELSIUS.toInt()}도 낮춤")
                    if (ComfortChange.FAN in changes) add("얼굴 쪽 바람")
                    if (ComfortChange.FRESH_AIR in changes) add("외기 유입")
                }
            }
            if (ComfortChange.LIGHT in changes) add("조명 차분하게")
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(", ") + " 맞췄어요"
    }
}
