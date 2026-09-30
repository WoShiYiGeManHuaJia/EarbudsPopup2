package com.woshiyigemanhuajia.btpopup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.ui.MainActivity
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 守护服务：与蓝牙监听服务互拉，做双服务保活。
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "BtGuard"
        private const val CHANNEL_ID = "bt_popup_guard"
        private const val NOTIF_ID = 1002

        fun start(context: Context) {
            val i = Intent(context, GuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "启动守护服务失败: " + t.message)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GuardService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止守护服务失败: " + t.message)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val checkTask = object : Runnable {
        override fun run() {
            if (Prefs.monitorEnabled && !BluetoothMonitorService.running) {
                Log.i(TAG, "监听服务不在运行，重新拉起")
                BluetoothMonitorService.start(this@GuardService)
            }
            if (Prefs.foregroundGuard) {
                main.postDelayed(this, 30_000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        startForegroundCompat()
        KeepAliveScheduler.schedule(this)
        main.postDelayed(checkTask, 20_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(checkTask)
        val restart = Prefs.foregroundGuard
        super.onDestroy()
        if (restart) {
            KeepAliveScheduler.schedule(this, 3000L)
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_MIN
                    ).apply { setShowBadge(false) }
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_notif_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "守护服务 startForeground 降级: " + t.message)
        }
    }
}
