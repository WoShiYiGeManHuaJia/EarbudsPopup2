package com.woshiyigemanhuajia.btpopup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.service.KeepAliveScheduler
import com.woshiyigemanhuajia.btpopup.util.Prefs

class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BtKeepAlive", "心跳触发，检查监听服务")
        Prefs.init(context)
        if (!Prefs.monitorEnabled) return
        if (!BluetoothMonitorService.running) {
            BluetoothMonitorService.start(context)
        }
        KeepAliveScheduler.schedule(context)
    }
}
