package com.jamkkanstop.app.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 실제로 바꾼 것. 화면·음성 안내에 쓴다. */
enum class ComfortChange { SEAT_HEAT, SEAT_VENT, WHEEL_HEAT, CABIN_TEMP, FAN, FRESH_AIR, LIGHT }

/**
 * 시트·공조·조명으로 "가는 동안 버티게" 돕고, 끝나면 원래대로 되돌린다.
 *
 * - 차종마다 지원 기능과 범위가 달라서 check*Capabilities로 확인한 것만 바꾼다.
 * - 하나가 실패해도 나머지는 계속한다 (전부 아니면 전무가 아님).
 * - 바꾼 값마다 되돌리는 동작을 기록해 두고 [restore]에서 역순으로 실행한다.
 * - Vehicle SDK의 콜백은 Binder 스레드에서 온다. 여기서는 코루틴으로 감싸 순서대로 처리한다.
 */
class ComfortController(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val seat get() = vehicle.getSeat()
    private val hvac get() = vehicle.getHVAC()
    private val light get() = vehicle.getLight()
    private val wheel get() = vehicle.getSteeringWheel()

    private val undo = ArrayDeque<() -> Unit>()

    /** 지금 켜져 있는 모드. 없으면 null. */
    var activeMode: ComfortMode? = null
        private set

    fun connect() {
        runCatching { vehicle.initialize() }.onFailure { Log.w(TAG, "Vehicle 초기화 실패", it) }
    }

    fun release() {
        restore()
        runCatching { vehicle.release() }
    }

    /** 모드를 적용하고 실제로 바꾼 항목을 돌려준다. 다른 모드가 켜져 있으면 먼저 되돌린다. */
    suspend fun apply(mode: ComfortMode): Set<ComfortChange> {
        if (activeMode != null) restore()
        activeMode = mode
        val changed = mutableSetOf<ComfortChange>()

        ensureHvacPower()
        applySeat(mode, changed)
        if (mode == ComfortMode.WARM) applySteeringWheel(changed)
        if (mode == ComfortMode.COOL) applyClimate(changed)
        applyCalmLight(changed)

        // set의 onComplete는 "요청 접수"다. Pleos 2.1.0 에뮬레이터는 접수만 하고 값을 바꾸지 않는다
        // (시스템 공조 바로 켜도 마찬가지). 실제 반영은 실차·Virtual Console에서 확인해야 한다.
        Log.d(TAG, "apply $mode → $changed")
        return changed
    }

    /** 바꾼 것을 원래대로. 기다리지 않고 요청만 보낸다 (화면을 닫을 때·앱 종료 때도 부를 수 있게). */
    fun restore() {
        while (undo.isNotEmpty()) {
            val action = undo.removeLast()
            runCatching(action).onFailure { Log.w(TAG, "restore failed", it) }
        }
        activeMode = null
    }

    // ---- 공조 전원 ----

    /**
     * 공조 전원이 꺼져 있으면 시트·핸들 열선, 온도 설정 요청이 접수만 되고 적용되지 않는다 (에뮬레이터에서 확인).
     * 꺼져 있을 때만 켜고, 되돌릴 때 다시 끈다.
     */
    private suspend fun ensureHvacPower() {
        val areas = capability<List<SeatArea>> { ok, err ->
            hvac.checkHvacPowerCapabilities({ c, l -> ok(l.takeIf { c.writable }) }, err)
        }
        areas.orEmpty().forEach { area ->
            val on = get<Boolean> { ok, err -> hvac.getHvacPowerState(area, ok, err) }
            Log.d(TAG, "hvac power area=${area.values.toList()} on=$on")
            if (on != false) return@forEach
            if (set { ok, err -> hvac.setHvacPower(area, true, { _, _ -> ok() }, err) }) {
                undo += { hvac.setHvacPower(area, false) }
            }
        }
        if (!areas.isNullOrEmpty()) kotlinx.coroutines.delay(300) // 전원이 켜진 뒤 다른 설정이 먹도록 잠깐 기다린다
    }

    // ---- 시트 ----

    private suspend fun applySeat(mode: ComfortMode, changed: MutableSet<ComfortChange>) {
        val tempBounds = capability<Map<SeatArea, AreaBounds<Int>>> { ok, err ->
            seat.checkSeatTemperatureCapabilities({ c, m -> ok(m.takeIf { c.writable }) }, err)
        }
        driverArea(tempBounds?.keys)?.let { area ->
            val levels = tempBounds!!.getValue(area).toLevels()
            val current = get<Int> { ok, err -> seat.getHvacSeatTemperature(area, ok, err) }
            Log.d(TAG, "seat temp area=${area.values.toList()} levels=$levels current=$current")
            ComfortRules.seatTemperature(mode, current, levels)?.takeIf { it != current }?.let { target ->
                if (set { ok, err -> seat.setHvacSeatTemperature(area, target, { _, _ -> ok() }, err) }) {
                    changed += ComfortChange.SEAT_HEAT
                    current?.let { undo += { seat.setHvacSeatTemperature(area, it) } }
                }
            }
        }

        val ventBounds = capability<Map<SeatArea, AreaBounds<Int>>> { ok, err ->
            seat.checkSeatVentilationCapabilities({ c, m -> ok(m.takeIf { c.writable }) }, err)
        }
        driverArea(ventBounds?.keys)?.let { area ->
            val levels = ventBounds!!.getValue(area).toLevels()
            val current = get<Int> { ok, err -> seat.getHvacSeatVentilationState(area, ok, err) }
            ComfortRules.seatVentilation(mode, current, levels)?.takeIf { it != current }?.let { target ->
                if (set { ok, err -> seat.setHvacSeatVentilation(area, target, { _, _ -> ok() }, err) }) {
                    changed += ComfortChange.SEAT_VENT
                    current?.let { undo += { seat.setHvacSeatVentilation(area, it) } }
                }
            }
        }
    }

    // ---- 핸들 열선 ----

    private suspend fun applySteeringWheel(changed: MutableSet<ComfortChange>) {
        val bounds = capability<AreaBounds<Int>> { ok, err ->
            wheel.checkHvacSteeringWheelHeatCapabilities({ c, b -> ok(b.takeIf { c.writable }) }, err)
        } ?: return
        val current = get<Int> { ok, err -> wheel.getHvacSteeringWheelHeatState(ok, err) }
        Log.d(TAG, "wheel heat levels=${bounds.toLevels()} current=$current")
        val target = ComfortRules.steeringWheelHeat(ComfortMode.WARM, bounds.toLevels()) ?: return
        if (target == current) return
        if (set { ok, err -> wheel.setHvacSteeringWheelHeat(target, { ok() }, err) }) {
            changed += ComfortChange.WHEEL_HEAT
            current?.let { undo += { wheel.setHvacSteeringWheelHeat(it) } }
        }
    }

    // ---- 공조: 온도 낮춤, 바람 한 단계 올림, 얼굴 쪽 송풍, 외기 유입 ----

    private suspend fun applyClimate(changed: MutableSet<ComfortChange>) {
        val tempCaps = capability<Pair<Map<SeatArea, AreaBounds<Float>>, Float?>> { ok, err ->
            hvac.checkHvacTemperatureCapabilities(
                { c, m, config -> ok((m to config?.incrementCelsius).takeIf { c.writable }) }, err,
            )
        }
        val zone = driverArea(tempCaps?.first?.keys)
        if (zone != null) {
            val bounds = tempCaps!!.first.getValue(zone)
            val current = get<Float> { ok, err -> hvac.getHvacTemperature(zone, TemperatureUnit.CELSIUS, ok, err) }
            ComfortRules.cabinTemperature(ComfortMode.COOL, current, bounds.minValue, bounds.maxValue, tempCaps.second)
                ?.let { target ->
                    if (set { ok, err -> hvac.setHvacTemperature(zone, target, TemperatureUnit.CELSIUS, { _, _ -> ok() }, err) }) {
                        changed += ComfortChange.CABIN_TEMP
                        current?.let { undo += { hvac.setHvacTemperature(zone, it, TemperatureUnit.CELSIUS) } }
                    }
                }
        }

        val fanCaps = capability<Map<SeatArea, AreaBounds<Int>>> { ok, err ->
            hvac.checkHvacFanSpeedCapabilities({ c, m -> ok(m.takeIf { c.writable }) }, err)
        }
        driverArea(fanCaps?.keys)?.let { area ->
            val current = get<Int> { ok, err -> hvac.getHvacFanSpeed(area, ok, err) }
            ComfortRules.fanSpeed(ComfortMode.COOL, current, fanCaps!!.getValue(area).toLevels())?.let { target ->
                if (set { ok, err -> hvac.setHvacFanSpeed(area, target, { _, _ -> ok() }, err) }) {
                    changed += ComfortChange.FAN
                    current?.let { undo += { hvac.setHvacFanSpeed(area, it) } }
                }
            }
        }

        val dirCaps = capability<Map<SeatArea, List<FanDirectionType>>> { ok, err ->
            hvac.checkHvacFanDirectionCapabilities({ c, m -> ok(m.takeIf { c.writable }) }, err)
        }
        driverArea(dirCaps?.keys)?.let { area ->
            if (FanDirectionType.FACE !in dirCaps!!.getValue(area)) return@let
            val current = get<FanDirectionType> { ok, err -> hvac.getHvacFanDirection(area, ok, err) }
            if (current == FanDirectionType.FACE) return@let
            if (set { ok, err -> hvac.setHvacFanDirection(area, FanDirectionType.FACE, { _, _ -> ok() }, err) }) {
                changed += ComfortChange.FAN
                current?.let { undo += { hvac.setHvacFanDirection(area, it) } }
            }
        }

        val recircAreas = capability<List<SeatArea>> { ok, err ->
            hvac.checkHvacRecirculationCapabilities({ c, l -> ok(l.takeIf { c.writable }) }, err)
        }
        driverArea(recircAreas)?.let { area ->
            val current = get<Boolean> { ok, err -> hvac.getHvacRecirculationState(area, ok, err) }
            if (current != true) return@let // 이미 외기 유입 중
            if (set { ok, err -> hvac.setHvacRecirculation(area, false, { _, _ -> ok() }, err) }) {
                changed += ComfortChange.FRESH_AIR
                undo += { hvac.setHvacRecirculation(area, true) }
            }
        }
    }

    // ---- 조명: 차분한 색·낮은 밝기. 깜빡임·애니메이션은 쓰지 않는다. ----

    private suspend fun applyCalmLight(changed: MutableSet<ComfortChange>) {
        // 주행 모드에 따라 색이 자동으로 바뀌는 기능이 켜져 있으면 우리 색을 덮어쓴다. 잠시 끈다.
        val autoColor = get<Boolean> { ok, err -> light.getAmbientColorAutoByDriveModeEnabled(ok, err) }
        if (autoColor == true && set { ok, err -> light.setAmbientColorAutoByDriveModeEnabled(false, { ok() }, err) }) {
            undo += { light.setAmbientColorAutoByDriveModeEnabled(true) }
        }

        val colorAreas = capability<List<SeatArea>> { ok, err ->
            light.checkAmbientLightBrightnessColorCapabilities({ c, l -> ok(l.takeIf { c.writable }) }, err)
        }
        colorAreas.orEmpty().forEach { area ->
            val current = get<Int> { ok, err -> light.getAmbientLightBrightnessColor(area, ok, err) }
            if (current == ComfortRules.CALM_COLOR) return@forEach
            if (set { ok, err -> light.setAmbientLightBrightnessColor(area, ComfortRules.CALM_COLOR, { _, _ -> ok() }, err) }) {
                changed += ComfortChange.LIGHT
                current?.let { undo += { light.setAmbientLightBrightnessColor(area, it, { _, _ -> }, {}) } }
            }
        }

        val brightCaps = capability<Map<SeatArea, AreaBounds<Int>>> { ok, err ->
            light.checkAmbientLightBrightnessCapabilities({ c, m -> ok(m.takeIf { c.writable }) }, err)
        }
        brightCaps.orEmpty().forEach { (area, bounds) ->
            val current = get<Int> { ok, err -> light.getAmbientLightBrightness(area, ok, err) }
            val target = ComfortRules.calmBrightness(current, bounds.toLevels()) ?: return@forEach
            if (set { ok, err -> light.setAmbientLightBrightness(area, target, { _, _ -> ok() }, err) }) {
                changed += ComfortChange.LIGHT
                current?.let { undo += { light.setAmbientLightBrightness(area, it, { _, _ -> }, {}) } }
            }
        }
    }

    // ---- 콜백 → 코루틴 ----

    private val CapabilityConfig.writable
        get() = accessType == AccessType.WRITE || accessType == AccessType.READ_WRITE

    private fun AreaBounds<Int>.toLevels() = Levels(minValue, maxValue)

    /** 운전석(1열 왼쪽)이 들어 있는 구역. 없으면 첫 구역. 구역 객체는 차량이 알려준 것을 그대로 써야 한다. */
    private fun driverArea(areas: Collection<SeatArea>?): SeatArea? =
        areas?.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas?.firstOrNull()

    private suspend fun <T> capability(call: (ok: (T?) -> Unit, err: (Exception) -> Unit) -> Unit): T? =
        get(call)

    private suspend fun <T> get(call: (ok: (T?) -> Unit, err: (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(v: T?) {
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(v)
                }
                try {
                    call({ finish(it) }, { e -> Log.d(TAG, "not available: ${e.message}"); finish(null) })
                } catch (e: Exception) { // register 계열은 권한이 없으면 바로 던진다
                    Log.w(TAG, "vehicle call threw", e)
                    finish(null)
                }
            }
        }

    /** set 요청이 받아들여졌으면 true. */
    private suspend fun set(call: (ok: () -> Unit, err: (Exception) -> Unit) -> Unit): Boolean =
        get<Boolean> { ok, err -> call({ ok(true) }, err) } == true

    private companion object {
        const val TAG = "ComfortController"
        const val TIMEOUT_MS = 3_000L
    }
}
