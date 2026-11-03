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
import com.jamkkanstop.app.pleos.VoiceInput
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    /** 화장실에 도착. [resumesOriginal]이면 내비가 원래 목적지로 이어서 안내한다. */
    data class Arrived(val placeName: String, val resumesOriginal: Boolean) : UrgentUiState
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
class UrgentStopViewModel(private val app: Application) : AndroidViewModel(app) {

    private val navi = NaviGateway(app)
    private val location = LocationSource(app, navi)
    private val voice = VoiceGuide(app)
    private val ears = VoiceInput(app)
    private val comfort = ComfortController(app)
    private var listenJob: Job? = null
    private var arrivalJob: Job? = null
    private var comfortJob: Job? = null
    // 전국 데이터(약 2만 3천 곳)는 읽는 데 시간이 걸린다. 버튼을 누르기 전에 미리 읽어둔다.
    private val planner = viewModelScope.async(Dispatchers.IO) {
        UrgentStopPlanner(ToiletRepository.fromAssets(app), navi)
    }

    private val _state = MutableStateFlow<UrgentUiState>(UrgentUiState.Idle)
    val state: StateFlow<UrgentUiState> = _state.asStateFlow()

    /** 음성 명령을 듣는 중인지. 화면에 마이크 표시를 띄운다. */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

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
        ears.connect()
        comfort.connect()
    }

    fun onUrgent() {
        if (job?.isActive == true) return
        // 버튼을 누른 지금(화면이 보일 때) 시작해야 한다. 경로를 넣으면 내비가 화면을 가져가 앱이 백그라운드로 간다.
        GuidingSessionService.start(app, "화장실 찾는 중")
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
            GuidingSessionService.start(app, "${plan.best.toilet.name}(으)로 안내 중")
            watchArrival(plan.best, insert)
            sayThenListen(Announcements.guiding(plan, insert))
        }
    }

    /** 예비 후보로 바꾼다. 바꾼 뒤에는 원래 1순위가 예비가 된다. */
    fun onSwitchToBackup() {
        stopListening()
        val current = _state.value as? UrgentUiState.Guiding ?: return
        val backup = current.plan.backup ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch(crashGuard) {
            val insert = navi.replaceStop(current.insert, backup.toilet)
            val plan = StopPlan(best = backup, backup = current.plan.best)
            // 버티게 돕기 상태는 그대로 이어간다.
            val comfortNow = (_state.value as? UrgentUiState.Guiding)?.comfort ?: current.comfort
            _state.value = UrgentUiState.Guiding(plan, insert, comfortNow)
            watchArrival(plan.best, insert)
            val askComfort = comfortNow == ComfortStatus.Offer
            val text = Announcements.guiding(plan, insert, askComfort)
            if (askComfort) sayThenListen(text) else voice.say(text)
        }
    }

    /** 복통 모드(따뜻하게) / 식은땀 모드(시원하게). */
    fun onComfort(mode: ComfortMode) {
        if (_state.value !is UrgentUiState.Guiding) return
        stopListening()
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
        stopListening()
        comfortJob?.cancel()
        comfort.restore()
        updateComfort(ComfortStatus.Offer)
    }

    fun onDismiss() {
        arrivalJob?.cancel()
        stopListening()
        voice.stop()
        job?.cancel()
        comfortJob?.cancel()
        comfort.restore()
        _state.value = UrgentUiState.Idle
        GuidingSessionService.stop(app)
    }

    /**
     * 안내를 끝까지 말한 뒤 한 번 듣고, 알아들은 명령을 실행한다.
     * 운전 중에 화면을 누르지 않아도 되게 하는 것이 목적이다. 못 알아들으면 조용히 넘어간다 (재촉하지 않음).
     */
    private fun sayThenListen(text: String) {
        stopListening()
        listenJob = viewModelScope.launch(crashGuard) {
            voice.sayAndWait(text)
            _listening.value = true
            val heard = try {
                ears.listenOnce()
            } finally {
                _listening.value = false
            }
            listenJob = null // 아래 명령 처리에서 stopListening()이 자기 자신을 취소하지 않도록
            handle(VoiceCommand.parse(heard), heard)
        }
    }

    private fun handle(command: VoiceCommand?, heard: String?) {
        Log.d(TAG, "voice command: \"$heard\" → $command")
        if (_state.value !is UrgentUiState.Guiding) return
        when (command) {
            is VoiceCommand.Comfort -> onComfort(command.mode)
            VoiceCommand.Backup -> onSwitchToBackup()
            VoiceCommand.ComfortOff -> onComfortOff()
            VoiceCommand.Dismiss -> onDismiss()
            VoiceCommand.Nothing -> voice.say("알겠어요. 천천히 가세요.")
            null -> Unit
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        if (_listening.value) ears.stop()
        _listening.value = false
    }

    /**
     * 도착을 지켜본다. 내비 도착 이벤트(이름이 같을 때)와 위치(화장실 100m 안) 중 먼저 오는 것을 쓴다.
     * 도착하면 바꾼 시트·공조·조명을 되돌리고, 앱을 붙잡아 두던 서비스를 끝낸다.
     */
    private fun watchArrival(target: StopCandidate, insert: StopInsertResult) {
        arrivalJob?.cancel()
        arrivalJob = viewModelScope.launch(crashGuard) {
            val byNavi = navi.arrivals.filter { Arrival.isSamePlace(it, target.toilet) }
            val byLocation = flow {
                while (true) {
                    delay(LOCATION_CHECK_MS)
                    location.current()?.let { emit(it) }
                }
            }.filter { Arrival.isNear(it.latitude, it.longitude, target.toilet, target.straightMeters) }
            merge(byNavi.map { "navi" }, byLocation.map { "location" }).first().also {
                Log.d(TAG, "arrived at ${target.toilet.name} (by $it)")
            }
            onArrived(target, insert)
        }
    }

    private fun onArrived(target: StopCandidate, insert: StopInsertResult) {
        stopListening()
        comfortJob?.cancel()
        comfort.restore()
        val resumes = insert.confirmed && insert.mode != StopInsertMode.AS_DESTINATION
        _state.value = UrgentUiState.Arrived(target.toilet.name, resumes)
        voice.say(Announcements.arrived(resumes))
        GuidingSessionService.stop(app)
    }

    private fun updateComfort(status: ComfortStatus) {
        val s = _state.value as? UrgentUiState.Guiding ?: return
        _state.value = s.copy(comfort = status)
    }

    private fun fail(message: String) {
        _state.value = UrgentUiState.Failed(message)
        voice.say(message)
        GuidingSessionService.stop(app)
    }

    override fun onCleared() {
        GuidingSessionService.stop(app)
        comfort.release() // 바꾼 시트·공조·조명을 되돌리고 연결 해제
        ears.release()
        voice.release()
        location.release()
        navi.release()
    }

    private companion object {
        const val TAG = "UrgentStop"
        const val LOCATION_CHECK_MS = 10_000L
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
        val ask = if (askComfort) " 배가 아프면 배 아파, 식은땀이 나면 식은땀이라고 말해 주세요." else ""
        return "$head $route 서두르지 않아도 괜찮아요.$ask"
    }

    fun arrived(resumesOriginal: Boolean): String =
        if (resumesOriginal) "도착했어요. 천천히 다녀오세요. 출발하면 원래 목적지로 이어서 안내해요."
        else "도착했어요. 천천히 다녀오세요."

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
