package com.jamkkanstop.app.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/**
 * 음성 안내. 네트워크·앱 등록 없이 동작하도록 차량 내장(On-device) 엔진을 쓴다.
 */
class VoiceGuide(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false

    fun connect() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { tts.setLocale(Locale.KOREA) }
                    ready = true
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "TTS 초기화 실패", it) }
    }

    fun say(text: String) {
        if (!ready) {
            Log.i(TAG, "TTS 준비 전, 건너뜀: $text")
            return
        }
        runCatching {
            tts.stop()
            tts.speak(text)
        }.onFailure { Log.w(TAG, "TTS 재생 실패", it) }
    }

    fun release() {
        runCatching { tts.stop() }
        runCatching { tts.release() }
    }

    private companion object {
        const val TAG = "VoiceGuide"
    }
}
