package com.woshiyigemanhuajia.btpopup.battery

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.util.Log

/**
 * 解析 HFP 厂商私有事件中的电量上报。
 *
 * 绝大多数 TWS 通过 AT+IPHONEACCEV 上报：
 *   AT+IPHONEACCEV=<count>,<key1>,<val1>,<key2>,<val2>...
 *   key=1 -> 电量档位 val(0..9)，换算为 (val+1)*10 %
 *   key=2 -> 是否在充电仓/充电中
 *
 * 部分厂商（华为 / 三星等）使用 AT+XEVENT / AT+BATT 自定义格式，
 * 这里做了宽松解析，尽量把能识别的电量抠出来。
 */
object HfpBatteryParser {

    private const val TAG = "HfpBattery"

    /** 事件命令名 -> 参数 */
    fun onVendorEvent(intent: Intent): Pair<BluetoothDevice, BatteryInfo>? {
        val device: BluetoothDevice? = try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (t: Throwable) {
            null
        } ?: return null

        val cmd = intent.getStringExtra("android.bluetooth.headset.profile.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD")
            ?: return null
        val args: Array<Any>? = try {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("android.bluetooth.headset.profile.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS") as? Array<Any>
        } catch (t: Throwable) {
            null
        }

        val cmdUpper = cmd.uppercase()
        Log.d(TAG, "vendor event cmd=" + cmd + " args=" + (args?.joinToString() ?: "null"))

        return when {
            cmdUpper.contains("IPHONEACCEV") -> parseIphoneAccEv(device, args)
            cmdUpper.contains("XEVENT") -> parseGeneric(device, args)
            cmdUpper.contains("BATT") -> parseGeneric(device, args)
            else -> null
        }
    }

    private fun toInt(o: Any?): Int = when (o) {
        is Int -> o
        is Long -> o.toInt()
        is Short -> o.toInt()
        is String -> o.toIntOrNull() ?: -1
        else -> -1
    }

    private fun parseIphoneAccEv(device: BluetoothDevice, args: Array<Any>?): Pair<BluetoothDevice, BatteryInfo>? {
        if (args == null || args.size < 3) return null
        val count = toInt(args[0])
        if (count <= 0) return null

        var level = -1
        var charging = false
        var i = 1
        while (i + 1 < args.size) {
            val k = toInt(args[i])
            val v = toInt(args[i + 1])
            when (k) {
                1 -> if (v in 0..9) level = (v + 1) * 10
                2 -> charging = v == 1
            }
            i += 2
        }
        if (level < 0) return null
        val name = BatteryRepository.safeName(device)
        val address = device.address ?: return null
        val info = BatteryRepository.update(address, name) { cur ->
            // IPHONEACCEV 只给整体值；若左右耳已单独拿到过则保留
            cur.copy(
                overall = level,
                charging = charging,
                source = if (cur.hasSplit) cur.source else "HFP 厂商上报",
                updatedAt = System.currentTimeMillis()
            )
        }
        return device to info
    }

    /**
     * 宽松解析类似 "+XEVENT=BATTERY,1,85" 这种结构，
     * 不同厂商格式差异较大，只提取第一个 0..100 的数值。
     */
    private fun parseGeneric(device: BluetoothDevice, args: Array<Any>?): Pair<BluetoothDevice, BatteryInfo>? {
        if (args == null || args.isEmpty()) return null
        val nums = args.map { toInt(it) }
        val level = nums.firstOrNull { it in 0..100 } ?: return null
        val name = BatteryRepository.safeName(device)
        val address = device.address ?: return null
        val info = BatteryRepository.update(address, name) { cur ->
            cur.copy(overall = level, source = "HFP 厂商上报", updatedAt = System.currentTimeMillis())
        }
        return device to info
    }
}
