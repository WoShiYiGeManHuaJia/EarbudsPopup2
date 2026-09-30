package com.woshiyigemanhuajia.btpopup.battery

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * 通过 BLE GATT 标准 Battery Service(0x180F) 读取电量。
 * 部分 TWS 耳机在 LE 侧暴露该服务，可拿到更精确的数值。
 */
object GattBatteryReader {

    private const val TAG = "GattBattery"
    private const val TIMEOUT_MS = 8000L

    private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val handler = Handler(Looper.getMainLooper())

    fun read(context: Context, device: BluetoothDevice, address: String, onResult: (Int) -> Unit) {
        var gatt: BluetoothGatt? = null
        var finished = false

        val timeout = Runnable {
            if (!finished) {
                finished = true
                try { gatt?.disconnect(); gatt?.close() } catch (t: Throwable) { }
            }
        }

        try {
            gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { g.discoverServices() } catch (t: Throwable) { }
                    } else {
                        if (!finished) finished = true
                        handler.removeCallbacks(timeout)
                        try { g.close() } catch (t: Throwable) { }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val ch = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL_CHAR)
                    if (ch == null) {
                        handler.removeCallbacks(timeout)
                        if (!finished) finished = true
                        try { g.disconnect(); g.close() } catch (t: Throwable) { }
                        return
                    }
                    try { g.readCharacteristic(ch) } catch (t: Throwable) { }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    handleValue(g, characteristic, status, address, onResult)
                    if (!finished) finished = true
                    handler.removeCallbacks(timeout)
                    try { g.disconnect(); g.close() } catch (t: Throwable) { }
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    handleValue(g, characteristic, status, address, onResult, value)
                    if (!finished) finished = true
                    handler.removeCallbacks(timeout)
                    try { g.disconnect(); g.close() } catch (t: Throwable) { }
                }
            }, BluetoothDevice.TRANSPORT_LE)

            handler.postDelayed(timeout, TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "GATT 读取失败: " + t.message)
        }
    }

    private fun handleValue(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        address: String,
        onResult: (Int) -> Unit,
        value: ByteArray? = null
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        val raw = value ?: characteristic.value ?: return
        if (raw.isEmpty()) return
        val level = raw[0].toInt() and 0xFF
        if (level in 0..100) {
            Log.i(TAG, "GATT 电量=" + level + " addr=" + address)
            BatteryRepository.update(address, null) { cur ->
                cur.copy(overall = level, source = "BLE GATT", updatedAt = System.currentTimeMillis())
            }
            onResult(level)
        }
    }
}
