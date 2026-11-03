package com.jamkkanstop.app.urgent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jamkkanstop.app.MainActivity

/**
 * 화장실로 안내하는 동안 앱을 살려두는 포그라운드 서비스.
 *
 * 경로를 넣으면 내비가 화면을 가져가고, 이 앱은 백그라운드로 밀려 시스템이 프로세스를 얼린다
 * (에뮬레이터 로그: "freezing com.jamkkanstop.app"). 얼면 음성 명령 듣기가 멈추지 않고,
 * 도착했을 때 시트·공조를 되돌릴 수도 없다. 실제 일은 ViewModel이 하고, 이 서비스는 프로세스만 유지한다.
 */
class GuidingSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "화장실로 안내 중"
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(title),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }.onFailure {
            Log.w(TAG, "포그라운드 서비스 시작 실패", it)
            stopSelf()
        }
        return START_NOT_STICKY // 앱이 죽었으면 되살리지 않는다 (안내 상태가 없으니)
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "화장실 안내", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText("눌러서 뿌지직 열기")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "GuidingSession"
        private const val CHANNEL_ID = "guiding"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "title"

        /** 화면이 보일 때(버튼을 누른 직후) 불러야 한다. 백그라운드에서는 시작이 막힐 수 있다. */
        fun start(context: Context, title: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GuidingSessionService::class.java).putExtra(EXTRA_TITLE, title),
                )
            }.onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GuidingSessionService::class.java)) }
        }
    }
}
