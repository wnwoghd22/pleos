package com.jamkkanstop.app.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 한 번 듣기. 차량 내장(On-device) 음성 인식을 쓴다.
 *
 * Pleos에서 일반 앱이 쓸 수 있는 건 request() 한 번 듣기뿐이다.
 * 호출어·상시 듣기(AmbientAi)는 시스템 앱 전용이라 "말만 하면 시작"은 만들 수 없다.
 */
class VoiceInput(context: Context) {

    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false
    @Volatile private var reply: CompletableDeferred<String?>? = null
    @Volatile private var lastText: String? = null

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            lastText = text
            if (isFinal) reply?.complete(text)
        }

        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onStartedRecognition() = Unit

        // 말이 끝났는데 최종 결과가 따로 오지 않으면 마지막 중간 결과를 쓴다.
        override fun onEndedRecognition() { reply?.complete(lastText) }

        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "STT 오류: $errorType")
            reply?.complete(null)
        }

        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun connect() {
        runCatching {
            stt.addListener(listener)
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { stt.setLocale(Locale.KOREA) }
                    ready = true
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "STT 초기화 실패: $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "STT 초기화 실패", it) }
    }

    /** 한 문장을 듣고 글자로 돌려준다. 못 알아들었거나 시간이 지나면 null. */
    suspend fun listenOnce(timeoutMs: Long = 8_000): String? {
        if (!ready) {
            Log.i(TAG, "STT 준비 전, 건너뜀")
            return null
        }
        val done = CompletableDeferred<String?>()
        reply?.complete(null)
        reply = done
        lastText = null
        return try {
            runCatching { stt.request() }.onFailure {
                Log.w(TAG, "STT 요청 실패", it)
                done.complete(null)
            }
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            if (!done.isCompleted) runCatching { stt.stop() }
            reply = null
        }.also { Log.d(TAG, "heard: $it") }
    }

    fun stop() {
        reply?.complete(null)
        runCatching { stt.stop() }
    }

    fun release() {
        stop()
        runCatching { stt.removeListener(listener) }
        runCatching { stt.release() }
    }

    private companion object {
        const val TAG = "VoiceInput"
    }
}
