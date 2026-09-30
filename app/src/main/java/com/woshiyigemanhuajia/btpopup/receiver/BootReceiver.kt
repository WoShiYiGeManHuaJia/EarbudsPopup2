package com.woshiyigemanhuajia.btpopup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.util.Prefs

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("BtBoot", "开机广播: " + action)
        Prefs.init(context)
        if (!Prefs.bootStart || !Prefs.monitorEnabled) return
        BluetoothMonitorService.start(context)
    }
}
