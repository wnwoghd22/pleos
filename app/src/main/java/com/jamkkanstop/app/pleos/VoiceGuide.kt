package com.jamkkanstop.app.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 음성 안내. 네트워크·앱 등록 없이 동작하도록 차량 내장(On-device) 엔진을 쓴다.
 */
class VoiceGuide(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false

    /** 지금 재생 중인 문장이 끝나면 완료된다. 끝(onDone)·중단(onStop)·오류 모두 "끝남"으로 본다. */
    @Volatile private var finished: CompletableDeferred<Unit>? = null
    /** 지금 문장이 실제로 재생을 시작했는지. 앞 문장을 끊을 때 오는 onStop을 새 문장의 끝으로 착각하지 않기 위해. */
    @Volatile private var started = false

    private val events = object : EventListener {
        override fun onStart() { started = true }
        override fun onReady() = Unit
        override fun onDone() { if (started) finished?.complete(Unit) }
        override fun onStop() { if (started) finished?.complete(Unit) }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS 오류: $errorType")
            finished?.complete(Unit)
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun connect() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // 이벤트 리스너는 초기화가 끝난 뒤에 붙여야 한다. 먼저 붙이면 초기화가 실패한다 (에뮬레이터에서 확인).
                    runCatching { tts.addEventListener(events) }.onFailure { Log.w(TAG, "TTS 리스너 등록 실패", it) }
                    runCatching { tts.setLocale(Locale.KOREA) }
                    ready = true
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "TTS 초기화 실패", it) }
    }

    /** 말하고 바로 돌아온다. 앞 문장은 끊는다. */
    fun say(text: String) {
        start(text)
    }

    /**
     * 말하고 끝날 때까지 기다린다. 음성 인식을 바로 이어서 할 때 쓴다
     * (안내 음성을 마이크가 듣지 않도록).
     */
    suspend fun sayAndWait(text: String) {
        val done = start(text) ?: return
        // 끝 신호가 오지 않는 경우에 대비. 한국어 TTS는 대략 초당 6~7자.
        withTimeoutOrNull(2_000L + text.length * 250L) { done.await() }
    }

    private fun start(text: String): CompletableDeferred<Unit>? {
        if (!ready) {
            Log.i(TAG, "TTS 준비 전, 건너뜀: $text")
            return null
        }
        finished?.complete(Unit)
        val done = CompletableDeferred<Unit>()
        return runCatching {
            tts.stop()
            started = false
            finished = done
            tts.speak(text)
            done
        }.onFailure { Log.w(TAG, "TTS 재생 실패", it) }.getOrNull()
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun release() {
        runCatching { tts.removeEventListener(events) }
        runCatching { tts.stop() }
        runCatching { tts.release() }
    }

    private companion object {
        const val TAG = "VoiceGuide"
    }
}
