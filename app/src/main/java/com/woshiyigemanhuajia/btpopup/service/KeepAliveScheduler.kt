package com.woshiyigemanhuajia.btpopup.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.woshiyigemanhuajia.btpopup.receiver.KeepAliveReceiver

/**
 * 用 AlarmManager 做兜底心跳：即使服务被系统回收，
 * 也能在 15 分钟内被重新拉起。
 */
object KeepAliveScheduler {

    private const val TAG = "KeepAlive"
    private const val REQUEST_CODE = 1001
    const val ACTION_KEEP_ALIVE = "com.woshiyigemanhuajia.btpopup.ACTION_KEEP_ALIVE"
    private const val INTERVAL_MS = 15 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + delayMs
        val pi = pendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "精确闹钟不可用，降级: " + t.message)
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                Log.w(TAG, "调度心跳失败: " + t2.message)
            }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pendingIntent(context))
        } catch (t: Throwable) {
            Log.w(TAG, "取消心跳失败: " + t.message)
        }
    }
}
