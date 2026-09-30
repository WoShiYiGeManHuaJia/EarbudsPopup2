package com.woshiyigemanhuajia.btpopup.battery

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 耳机电量的统一入口。
 *
 * 数据来源优先级（全部为系统真实数据，不做任何估算）：
 *  1. Android 14+ 官方 BluetoothDevice.getBatteryLevel()
 *  2. 反射调用隐藏实现 getBatteryLevel()（老版本系统）
 *  3. 系统隐藏广播 BATTERY_LEVEL_CHANGED 缓存值
 *  4. HFP 厂商事件 AT+IPHONEACCEV / +XEVENT 解析值（可拿到部分耳机的分体电量）
 *  5. BLE GATT Battery Service 读取值
 */
object BatteryRepository {

    private const val TAG = "BtBattery"

    private val cache = ConcurrentHashMap<String, BatteryInfo>()

    fun key(address: String?) = (address ?: "").uppercase()

    fun get(address: String?): BatteryInfo? = address?.let { cache[key(it)] }

    fun all(): List<BatteryInfo> = cache.values.toList()

    fun put(info: BatteryInfo) {
        cache[key(info.address)] = info
    }

    fun update(address: String, name: String?, block: (BatteryInfo) -> BatteryInfo): BatteryInfo {
        val k = key(address)
        val base = cache[k] ?: BatteryInfo(name ?: BatteryInfo.UNKNOWN_NAME, address)
        val withName = if (base.name.isBlank() && !name.isNullOrBlank()) base.copy(name = name) else base
        val next = block(withName)
        cache[k] = next
        return next
    }

    fun remove(address: String?) {
        if (address != null) cache.remove(key(address))
    }

    /** 主动拉取一次最新值（系统 API + 缓存合并） */
    fun query(context: Context, device: BluetoothDevice, fallbackName: String?): BatteryInfo {
        val address = addressOf(device) ?: "00:00:00:00:00:00"
        val name = fallbackName?.takeIf { it.isNotBlank() } ?: safeName(device)
        var info = cache[key(address)] ?: BatteryInfo(name, address)
        if (info.name.isBlank()) info = info.copy(name = name)

        val sys = readSystemLevel(device)
        if (sys in 0..100 && sys != info.overall) {
            info = info.copy(overall = sys, updatedAt = System.currentTimeMillis())
        }
        if (sys in 0..100 && info.source.isBlank()) {
            info = info.copy(source = "系统蓝牙服务")
        }
        cache[key(address)] = info
        return info
    }

    /**
     * 系统真实电量，读不到返回 -1。
     * BluetoothDevice#getBatteryLevel() 在不同版本上均为隐藏 API，
     * 只能反射调用；调用失败时由广播 / GATT / HFP 等通路兜底。
     */
    fun readSystemLevel(device: BluetoothDevice): Int {
        try {
            val m = BluetoothDevice::class.java.getDeclaredMethod("getBatteryLevel")
            m.isAccessible = true
            val v = (m.invoke(device) as? Int) ?: -1
            if (v in 0..100) return v
        } catch (t: Throwable) {
            Log.w(TAG, "反射 getBatteryLevel 失败: " + t.message)
        }
        return -1
    }

    /** 读设备地址（Android 12+ 需要 BLUETOOTH_CONNECT）；任何异常都返回 null，绝不外抛 */
    fun addressOf(d: BluetoothDevice): String? = try {
        d.address
    } catch (t: Throwable) {
        null
    }

    fun safeName(d: BluetoothDevice): String = try {
        (d.name ?: "").takeIf { it.isNotBlank() } ?: addressOf(d) ?: BatteryInfo.UNKNOWN_NAME
    } catch (t: Throwable) {
        // 兜底分支也必须安全：旧写法在 catch 里再读一次 address，权限缺失时会二次抛异常，
        // 异常逃出蓝牙广播的 onReceive 后系统会连带回收监听服务 —— 这正是"怎么都不弹窗"的根因之一
        addressOf(d) ?: BatteryInfo.UNKNOWN_NAME
    }
}
