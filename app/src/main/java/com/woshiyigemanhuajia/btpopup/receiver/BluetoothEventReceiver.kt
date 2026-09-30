package com.woshiyigemanhuajia.btpopup.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 静态注册的兜底接收器：进程被杀后，蓝牙连接仍能拉起监听服务。
 */
class BluetoothEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) return

        Prefs.init(context)
        if (!Prefs.monitorEnabled || !Prefs.autoPopup) return
        if (BluetoothMonitorService.running) return
        Log.i("BtEventReceiver", "蓝牙事件兜底拉起服务: " + action)
        BluetoothMonitorService.start(context)
    }
}
