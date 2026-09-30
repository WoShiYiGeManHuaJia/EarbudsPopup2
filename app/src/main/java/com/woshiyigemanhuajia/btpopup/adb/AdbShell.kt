package com.woshiyigemanhuajia.btpopup.adb

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 通过 Stellar / Shizuku（同类 ADB 权限代理）直接以 shell 身份执行命令，
 * 把「悬浮窗、通知、蓝牙、电池优化、后台运行」等一堆分散的权限开关
 * 收敛成一个按钮。
 */
object AdbShell {

    private const val TAG = "AdbShell"

    data class Result(val ok: Boolean, val code: Int, val out: String, val err: String) {
        val brief: String
            get() = when {
                ok -> out.trim().ifBlank { "OK" }
                else -> err.trim().ifBlank { out.trim().ifBlank { "执行失败" } }
            }
    }

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        Log.w(TAG, "pingBinder 失败: " + t.message)
        false
    }

    fun hasPermission(): Boolean = try {
        if (!binderAlive()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (binderAlive() && !hasPermission()) Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission 失败: " + t.message)
        }
    }

    @Synchronized
    fun exec(cmd: String): Result {
        if (!binderAlive()) return Result(false, -1, "", "未连接 Stellar / Shizuku 服务")
        if (!hasPermission()) return Result(false, -1, "", "未授予 Stellar / Shizuku 权限")
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            Result(code == 0, code, out, err)
        } catch (t: Throwable) {
            Log.w(TAG, "exec 失败: " + cmd + " -> " + t.message)
            Result(false, -1, "", t.message ?: "执行异常")
        }
    }

    fun execAll(cmds: List<String>): List<Result> = cmds.map { exec(it) }

    /**
     * 一键授权所需的全部命令。
     * 已授予的条目会返回非 0，这里统一用 "|| true" 兜住，不影响整体流程。
     */
    fun buildGrantCommands(pkg: String): List<String> = listOf(
        "pm grant $pkg android.permission.BLUETOOTH_CONNECT || true",
        "pm grant $pkg android.permission.BLUETOOTH_SCAN || true",
        "pm grant $pkg android.permission.POST_NOTIFICATIONS || true",
        "pm grant $pkg android.permission.ACCESS_FINE_LOCATION || true",
        "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION || true",
        "appops set $pkg SYSTEM_ALERT_WINDOW allow || true",
        "appops set $pkg RUN_IN_BACKGROUND allow || true",
        "appops set $pkg RUN_ANY_IN_BACKGROUND allow || true",
        "appops set $pkg WAKE_LOCK allow || true",
        "appops set $pkg START_FOREGROUND allow || true",
        "appops set $pkg SCHEDULE_EXACT_ALARM allow || true",
        "appops set $pkg POST_NOTIFICATION allow || true",
        "cmd deviceidle whitelist +$pkg || true",
        "dumpsys deviceidle whitelist +$pkg || true",
        "am set-inactive $pkg false || true",
        "cmd appops set $pkg GET_USAGE_STATS allow || true"
    )

    fun buildGrantScript(pkg: String): String = buildGrantCommands(pkg).joinToString("\n")
}
