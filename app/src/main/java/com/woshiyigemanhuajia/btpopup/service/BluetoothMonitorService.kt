package com.woshiyigemanhuajia.btpopup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.App
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.battery.BatteryRepository
import com.woshiyigemanhuajia.btpopup.battery.GattBatteryReader
import com.woshiyigemanhuajia.btpopup.battery.HfpBatteryParser
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager
import com.woshiyigemanhuajia.btpopup.ui.MainActivity
import com.woshiyigemanhuajia.btpopup.util.Prefs

class BluetoothMonitorService : Service() {

    companion object {
        private const val TAG = "BtMonitor"
        private const val CHANNEL_ID = "bt_popup_monitor"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.woshiyigemanhuajia.btpopup.START_MONITOR"
        const val ACTION_STOP = "com.woshiyigemanhuajia.btpopup.STOP_MONITOR"

        /** 读不到蓝牙地址时的占位 key：宁可少一次去重，也不能因为读地址失败而不弹窗 */
        private const val FALLBACK_ADDRESS = "00:00:00:00:00:00"

        fun start(context: Context) {
            val i = Intent(context, BluetoothMonitorService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "启动监听服务失败: " + t.message)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BluetoothMonitorService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止监听服务失败: " + t.message)
            }
        }

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastDeviceAddress: String? = null
    private var lastTriggerAt = 0L
    private val refreshTasks = mutableListOf<Runnable>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.d(TAG, "onReceive: " + action)
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d = device(intent) ?: return
                    if (isAudioLike(d)) onConnected(d)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = device(intent) ?: return
                    onDisconnected(d)
                }
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                    val d = device(intent) ?: return
                    onBatteryBroadcast(d)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1)
                    val d = device(intent) ?: return
                    if (state == BluetoothA2dp.STATE_CONNECTED) onConnected(d)
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
                    val d = device(intent) ?: return
                    if (state == BluetoothHeadset.STATE_CONNECTED) onConnected(d)
                }
                "android.bluetooth.headset.profile.action.VENDOR_SPECIFIC_HEADSET_EVENT" -> {
                    val parsed = HfpBatteryParser.onVendorEvent(intent) ?: return
                    PopupOverlayManager.update(parsed.second)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        running = true
        createChannel()
        startForegroundCompat()
        registerAll()
        KeepAliveScheduler.schedule(this)
        if (Prefs.foregroundGuard) GuardService.start(this)
        Log.i(TAG, "监听服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterAll()
        val shouldRestart = Prefs.monitorEnabled
        super.onDestroy()
        if (shouldRestart) {
            KeepAliveScheduler.schedule(this, 3000L)
            if (Prefs.foregroundGuard) GuardService.start(this)
        }
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            if (Prefs.silentNotification) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(if (Prefs.silentNotification) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)

        if (Prefs.silentNotification) {
            builder.setSilent(true)
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            }
        }
        return builder.build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败，降级: " + t.message)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground 彻底失败: " + t2.message)
            }
        }
    }

    // ------------------------------------------------------------------ 广播注册

    private fun registerAll() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.headset.profile.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (t: Throwable) {
            Log.e(TAG, "注册蓝牙广播失败: " + t.message)
        }
    }

    private fun unregisterAll() {
        if (!registered) return
        try {
            unregisterReceiver(receiver)
        } catch (t: Throwable) {
            Log.w(TAG, "反注册失败: " + t.message)
        }
        registered = false
    }

    // ------------------------------------------------------------------ 事件处理

    private fun device(intent: Intent): BluetoothDevice? = try {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    } catch (t: Throwable) {
        null
    }

    /** Android 12+ 读 device.address 需要 BLUETOOTH_CONNECT；读不到也绝不能因此把弹窗丢掉 */
    private fun addressOf(device: BluetoothDevice): String? = try {
        device.address
    } catch (t: Throwable) {
        Log.w(TAG, "读取蓝牙地址失败: " + t.message)
        null
    }

    private fun onConnected(device: BluetoothDevice) {
        if (!Prefs.autoPopup) return
        val address = addressOf(device) ?: FALLBACK_ADDRESS
        val now = System.currentTimeMillis()
        if (address == lastDeviceAddress && now - lastTriggerAt < 3000) return
        lastDeviceAddress = address
        lastTriggerAt = now

        // 电量 / 设备名只是"锦上添花"：任何读取失败（权限、机型差异）都不允许挡住弹窗本身
        var name = ""
        var info: BatteryInfo? = null
        try {
            name = BatteryRepository.safeName(device)
            info = BatteryRepository.query(this, device, name)
        } catch (t: Throwable) {
            Log.e(TAG, "读取耳机信息失败: " + t.message)
        }
        Log.i(TAG, "检测到耳机连接，准备弹出弹窗: " + name.ifBlank { address })
        PopupOverlayManager.show(
            this,
            info ?: BatteryRepository.update(address, name) { it },
            Prefs.imageUri
        )
        scheduleRefresh(device, name)
    }

    private fun onDisconnected(device: BluetoothDevice) {
        val address = addressOf(device) ?: FALLBACK_ADDRESS
        BatteryRepository.remove(address)
        if (address.equals(lastDeviceAddress, true)) {
            PopupOverlayManager.dismiss()
            lastDeviceAddress = null
        }
    }

    private fun onBatteryBroadcast(device: BluetoothDevice) {
        val level = try {
            intentLevel(device)
        } catch (t: Throwable) {
            BatteryRepository.readSystemLevel(device)
        }
        val name = BatteryRepository.safeName(device)
        val info = BatteryRepository.update(addressOf(device) ?: FALLBACK_ADDRESS, name) { cur ->
            cur.copy(
                overall = if (level in 0..100) level else cur.overall,
                source = if (cur.source.isBlank()) "系统蓝牙服务" else cur.source,
                updatedAt = System.currentTimeMillis()
            )
        }
        PopupOverlayManager.update(info)
    }

    private fun intentLevel(device: BluetoothDevice): Int = BatteryRepository.readSystemLevel(device)

    /** 连接瞬间系统电量可能还没上报，做几次异步补偿刷新 */
    private fun scheduleRefresh(device: BluetoothDevice, name: String) {
        clearRefreshTasks()
        val address = addressOf(device) ?: FALLBACK_ADDRESS
        val delays = longArrayOf(600L, 1500L, 3000L, 5000L)
        delays.forEach { delay ->
            val task = Runnable {
                val info = BatteryRepository.query(this, device, name)
                PopupOverlayManager.update(info)
                if (delay == 3000L) {
                    GattBatteryReader.read(this, device, address) { }
                }
            }
            refreshTasks += task
            main.postDelayed(task, delay)
        }
    }

    private fun clearRefreshTasks() {
        refreshTasks.forEach { main.removeCallbacks(it) }
        refreshTasks.clear()
    }

    // ------------------------------------------------------------------ 设备判定

    private fun isAudioLike(device: BluetoothDevice): Boolean {
        val cls = try {
            device.bluetoothClass
        } catch (t: Throwable) {
            null
        }
        if (cls != null) {
            if (cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
            when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> return true
            }
        }
        val n = BatteryRepository.safeName(device).lowercase()
        return KEYWORDS.any { n.contains(it) }
    }

    private val KEYWORDS = listOf(
        "headphone", "headset", "earbud", "earbuds", "airpod", "buds", "freebuds",
        "wh-", "wf-", "powerbeats", "soundcore", "enco", "耳机", "beats", "jbl"
    )
}
