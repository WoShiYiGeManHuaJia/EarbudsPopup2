package com.woshiyigemanhuajia.btpopup.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.adb.AdbShell
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.battery.BatteryRepository
import com.woshiyigemanhuajia.btpopup.databinding.ActivityMainBinding
import com.woshiyigemanhuajia.btpopup.overlay.PopupOverlayManager
import com.woshiyigemanhuajia.btpopup.service.BluetoothMonitorService
import com.woshiyigemanhuajia.btpopup.service.GuardService
import com.woshiyigemanhuajia.btpopup.util.Prefs
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SHIZUKU = 10086
        private const val REQ_PERMS = 2002
        private const val REQ_PICK = 2001
        private const val MONITOR_CHANNEL = "bt_popup_monitor"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var previewStage: PopupPreviewStage
    private var loadingUi = false
    private val ui = Handler(Looper.getMainLooper())
    private val rebuildPreviewTask = Runnable { rebuildLivePreview() }

    private val binderReceived = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            runOnUiThread { refreshPermissions() }
        }
    }

    private val binderDead = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            runOnUiThread { refreshPermissions() }
        }
    }

    private val permListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            runOnUiThread {
                if (requestCode == REQ_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
                    toast("已获得 Stellar / Shizuku 权限，开始执行一键授权")
                    runOneKeyGrant()
                } else if (requestCode == REQ_SHIZUKU) {
                    toast("Stellar / Shizuku 权限被拒绝")
                }
                refreshPermissions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        previewStage = PopupPreviewStage(this, b.previewStage)

        setupSliders()
        setupSwitches()
        setupButtons()
        setupShizuku()

        if (Prefs.monitorEnabled && !BluetoothMonitorService.running) {
            BluetoothMonitorService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        syncFromPrefs()
        refreshPermissions()
        refreshLiveStatus()
        refreshPreview()
        refreshServiceStatus()
    }

    override fun onDestroy() {
        ui.removeCallbacks(rebuildPreviewTask)
        if (::previewStage.isInitialized) previewStage.destroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
            // ignore
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 滑块

    private fun setupSliders() {
        setupSeek(b.sbAlpha, b.tvAlphaVal, 0, 100,
            { Prefs.panelAlpha }, { Prefs.panelAlpha = it }) { "$it%" }

        setupSeek(b.sbImageHeight, b.tvImageHeightVal, 40, 600,
            { Prefs.imageHeightDp }, { Prefs.imageHeightDp = it }) { "${it}dp" }

        setupSeek(b.sbWidth, b.tvWidthVal, 20, 100,
            { Prefs.widthPercent }, { Prefs.widthPercent = it }) { "$it%" }

        setupSeek(b.sbHeight, b.tvHeightVal, 0, 1200,
            { if (Prefs.heightFixed) Prefs.heightDp else 0 },
            { v ->
                Prefs.heightFixed = v > 0
                if (v > 0) Prefs.heightDp = if (v < 120) 120 else v
            }) { if (it <= 0) "自动" else "${it}dp" }

        setupSeek(b.sbPosX, b.tvPosXVal, 0, 100,
            { Prefs.posXPercent }, { Prefs.posXPercent = it }) { posLabel(it) }

        setupSeek(b.sbPosY, b.tvPosYVal, 0, 100,
            { Prefs.posYPercent }, { Prefs.posYPercent = it }) { posLabel(it) }

        setupSeek(b.sbRadius, b.tvRadiusVal, 0, 200,
            { Prefs.cornerRadiusDp }, { Prefs.cornerRadiusDp = it }) { "${it}dp" }

        setupSeek(b.sbLandWidth, b.tvLandWidthVal, 20, 100,
            { Prefs.landWidthPercent }, { Prefs.landWidthPercent = it }) { "$it%" }

        setupSeek(b.sbLandHeight, b.tvLandHeightVal, 0, 800,
            { if (Prefs.landHeightFixed) Prefs.landHeightDp else 0 },
            { v ->
                Prefs.landHeightFixed = v > 0
                if (v > 0) Prefs.landHeightDp = if (v < 80) 80 else v
            }) { if (it <= 0) "自动" else "${it}dp" }

        setupSeek(b.sbLandMargin, b.tvLandMarginVal, 0, 120,
            { Prefs.landMarginDp }, { Prefs.landMarginDp = it }) { "${it}dp" }

        setupSeek(b.sbAnimDuration, b.tvAnimDurationVal, 60, 1500,
            { Prefs.animDuration }, { Prefs.animDuration = it }) { "${it}ms" }

        setupSeek(b.sbDismissDelay, b.tvDismissDelayVal, 0, 120,
            { Prefs.dismissDelayMs / 1000 }, { Prefs.dismissDelayMs = it * 1000 }) {
            if (it <= 0) "不自动关闭" else "${it}s"
        }

        setupImageScale()
        setupColorButtons()
    }

    private fun posLabel(v: Int): String = when {
        v < 20 -> "靠左/靠上 $v%"
        v > 80 -> "靠右/靠下 $v%"
        else -> "居中 $v%"
    }

    private fun setupSeek(
        bar: SeekBar,
        label: TextView,
        min: Int,
        max: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        fmt: (Int) -> String
    ) {
        bar.max = (max - min).coerceAtLeast(1)
        bar.progress = (get() - min).coerceIn(0, bar.max)
        label.text = fmt(get())
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = (min + progress).coerceIn(min, max)
                set(v)
                label.text = fmt(v)
                if (fromUser) refreshPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun syncFromPrefs() {
        loadingUi = true
        setSeekValue(b.sbAlpha, b.tvAlphaVal, 0, Prefs.panelAlpha) { "$it%" }
        setSeekValue(b.sbImageHeight, b.tvImageHeightVal, 40, Prefs.imageHeightDp) { "${it}dp" }
        setSeekValue(b.sbWidth, b.tvWidthVal, 20, Prefs.widthPercent) { "$it%" }
        setSeekValue(b.sbHeight, b.tvHeightVal, 0, if (Prefs.heightFixed) Prefs.heightDp else 0) {
            if (it <= 0) "自动" else "${it}dp"
        }
        setSeekValue(b.sbPosX, b.tvPosXVal, 0, Prefs.posXPercent) { posLabel(it) }
        setSeekValue(b.sbPosY, b.tvPosYVal, 0, Prefs.posYPercent) { posLabel(it) }
        setSeekValue(b.sbRadius, b.tvRadiusVal, 0, Prefs.cornerRadiusDp) { "${it}dp" }
        setSeekValue(b.sbLandWidth, b.tvLandWidthVal, 20, Prefs.landWidthPercent) { "$it%" }
        setSeekValue(b.sbLandHeight, b.tvLandHeightVal, 0, if (Prefs.landHeightFixed) Prefs.landHeightDp else 0) {
            if (it <= 0) "自动" else "${it}dp"
        }
        setSeekValue(b.sbLandMargin, b.tvLandMarginVal, 0, Prefs.landMarginDp) { "${it}dp" }
        setSeekValue(b.sbAnimDuration, b.tvAnimDurationVal, 60, Prefs.animDuration) { "${it}ms" }
        setSeekValue(b.sbDismissDelay, b.tvDismissDelayVal, 0, Prefs.dismissDelayMs / 1000) {
            if (it <= 0) "不自动关闭" else "${it}s"
        }

        b.swAutoPopup.isChecked = Prefs.autoPopup
        b.swForeground.isChecked = Prefs.foregroundGuard
        b.swSilentNotif.isChecked = Prefs.silentNotification
        b.swBootStart.isChecked = Prefs.bootStart
        b.swBatteryOpt.isChecked = isIgnoringBatteryOptimization()

        when (Prefs.animType) {
            "fade" -> b.chipFade.isChecked = true
            "scale" -> b.chipScale.isChecked = true
            "slide_top" -> b.chipSlideTop.isChecked = true
            "slide_bottom" -> b.chipSlideBottom.isChecked = true
            else -> b.chipSpring.isChecked = true
        }

        when (Prefs.imageScaleMode) {
            "fit" -> b.chipScaleFit.isChecked = true
            "stretch" -> b.chipScaleStretch.isChecked = true
            "center" -> b.chipScaleCenter.isChecked = true
            else -> b.chipScaleCrop.isChecked = true
        }
        refreshColorSwatches()
        loadingUi = false
    }

    private fun setSeekValue(bar: SeekBar, label: TextView, min: Int, value: Int, fmt: (Int) -> String) {
        bar.max = bar.max.coerceAtLeast(1)
        bar.progress = (value - min).coerceIn(0, bar.max)
        label.text = fmt(value)
    }

    // ------------------------------------------------------------------ 开关

    private fun setupSwitches() {
        b.swAutoPopup.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.autoPopup = v
        }

        b.swForeground.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.foregroundGuard = v
            if (v) {
                BluetoothMonitorService.start(this)
                GuardService.start(this)
            } else {
                GuardService.stop(this)
            }
        }

        b.swSilentNotif.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.silentNotification = v
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.deleteNotificationChannel(MONITOR_CHANNEL)
            } catch (t: Throwable) {
                // ignore
            }
            if (BluetoothMonitorService.running) {
                BluetoothMonitorService.stop(this)
                b.root.postDelayed({ BluetoothMonitorService.start(this) }, 400)
            }
        }

        b.swBootStart.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            Prefs.bootStart = v
        }

        b.swBatteryOpt.setOnCheckedChangeListener { _, v ->
            if (loadingUi) return@setOnCheckedChangeListener
            if (v && !isIgnoringBatteryOptimization()) {
                requestIgnoreBatteryOptimization()
            }
        }

        b.cgAnimType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (loadingUi) return@setOnCheckedStateChangeListener
            val type = when (checkedIds.firstOrNull()) {
                R.id.chipFade -> "fade"
                R.id.chipScale -> "scale"
                R.id.chipSlideTop -> "slide_top"
                R.id.chipSlideBottom -> "slide_bottom"
                else -> "spring"
            }
            Prefs.animType = type
        }
    }

    // ------------------------------------------------------------------ 按钮

    private fun setupButtons() {
        b.btnPickImage.setOnClickListener { pickImage() }
        b.btnClearImage.setOnClickListener {
            Prefs.imageUri = null
            refreshPreview()
        }
        b.btnQuickPreview.setOnClickListener { showTestPopup() }
        b.btnLandscapePreview.setOnClickListener { toggleLandscapePreview() }
        b.btnAdbGrant.setOnClickListener { runOneKeyGrant() }
        b.btnCopyAdb.setOnClickListener { copyAdbScript() }
        b.btnAutoStartSetting.setOnClickListener { openAutoStartSettings() }
    }

    private fun setupImageScale() {
        b.cgImageScale.setOnCheckedStateChangeListener { _, checkedIds ->
            if (loadingUi) return@setOnCheckedStateChangeListener
            Prefs.imageScaleMode = when (checkedIds.firstOrNull()) {
                R.id.chipScaleFit -> "fit"
                R.id.chipScaleStretch -> "stretch"
                R.id.chipScaleCenter -> "center"
                else -> "crop"
            }
            refreshPreview()
        }
    }

    private fun setupColorButtons() {
        b.btnPanelColor.setOnClickListener {
            pickColor("面板底色", Prefs.panelColor) { Prefs.panelColor = it }
        }
        b.btnTextColor.setOnClickListener {
            pickColor("文字颜色", Prefs.textColor) { Prefs.textColor = it }
        }
        b.btnAccentColor.setOnClickListener {
            pickColor("强调颜色", Prefs.accentColor) { Prefs.accentColor = it }
        }
    }

    private fun pickColor(title: String, current: Int, apply: (Int) -> Unit) {
        ColorPickerDialog(this, title, current) { picked ->
            apply(picked)
            refreshColorSwatches()
            refreshPreview()
        }.show()
    }

    private fun refreshColorSwatches() {
        paintSwatch(b.btnPanelColor, Prefs.panelColor)
        paintSwatch(b.btnTextColor, Prefs.textColor)
        paintSwatch(b.btnAccentColor, Prefs.accentColor)
    }

    private fun paintSwatch(view: TextView, color: Int) {
        val bg = ContextCompat.getDrawable(this, R.drawable.bg_color_swatch)?.mutate() as? GradientDrawable
            ?: GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
        bg.setColor(color)
        bg.setStroke(dp(1), if (isColorDark(color)) 0x33FFFFFF else 0x33000000)
        view.background = bg
        view.setTextColor(if (isColorDark(color)) Color.WHITE else 0xFF0F172A.toInt())
    }

    private fun isColorDark(color: Int): Boolean {
        val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return luminance < 140
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            toast("无法打开图片选择器")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                // 部分来源不支持持久化授权，忽略
            }
            Prefs.imageUri = uri.toString()
            refreshPreview()
        }
    }

    private fun refreshPreview() {
        if (!::previewStage.isInitialized) return
        previewStage.setInfo(BatteryRepository.all().maxByOrNull { it.updatedAt } ?: dummyInfo())
        previewStage.requestRender()
        syncPreviewMode()
        // 弹窗已在屏上时，改完参数立刻重建，做到所见即所得
        ui.removeCallbacks(rebuildPreviewTask)
        ui.postDelayed(rebuildPreviewTask, 220)
    }

    /** 横屏预览入口：竖屏持机时也能看到横屏弹窗的真实样式与位置 */
    private fun toggleLandscapePreview() {
        val next = !previewStage.isLandscape()
        previewStage.setLandscape(next)
        syncPreviewMode()
    }

    /** 预览模式切换后同步按钮文案 */
    private fun syncPreviewMode() {
        if (!::previewStage.isInitialized) return
        b.btnLandscapePreview.text = if (previewStage.isLandscape()) "竖屏预览" else "横屏预览"
    }

    private fun rebuildLivePreview() {
        if (!PopupOverlayManager.isShowing()) return
        if (!Settings.canDrawOverlays(this)) return
        PopupOverlayManager.show(this, BatteryRepository.all().maxByOrNull { it.updatedAt } ?: dummyInfo(), Prefs.imageUri)
    }

    private fun dummyInfo(): BatteryInfo =
        BatteryInfo("测试耳机", "00:11:22:33:44:55", 88, 76, 54, 88, false, "预览数据")

    private fun showTestPopup() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            requestOverlayPermission()
            return
        }
        val info = BatteryRepository.all().maxByOrNull { it.updatedAt } ?: dummyInfo()
        PopupOverlayManager.show(this, info, Prefs.imageUri)
    }

    // ------------------------------------------------------------------ 权限

    private fun refreshPermissions() {
        val rows = mutableListOf<PermRow>()
        rows += PermRow("悬浮窗权限（系统级弹窗）", Settings.canDrawOverlays(this)) { requestOverlayPermission() }
        rows += PermRow("通知权限", hasPermission(Manifest.permission.POST_NOTIFICATIONS)) { requestRuntimePerms() }
        rows += PermRow("蓝牙连接权限", hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) { requestRuntimePerms() }
        rows += PermRow("定位权限（部分机型扫描必需）", hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) { requestRuntimePerms() }
        rows += PermRow("忽略电池优化", isIgnoringBatteryOptimization()) { requestIgnoreBatteryOptimization() }
        rows += PermRow(
            "Stellar / Shizuku 权限",
            AdbShell.hasPermission()
        ) { runOneKeyGrant() }

        b.permContainer.removeAllViews()
        rows.forEach { addPermRow(it) }

        val shizukuText = when {
            !AdbShell.binderAlive() -> "Stellar / Shizuku：未连接（请先打开 Stellar 并启动服务）"
            AdbShell.hasPermission() -> "Stellar / Shizuku：已连接且已授权"
            else -> "Stellar / Shizuku：已连接，等待授权"
        }
        b.tvShizukuState.text = shizukuText
    }

    private data class PermRow(val title: String, val granted: Boolean, val action: () -> Unit)

    private fun addPermRow(row: PermRow) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = row.title
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val state = TextView(this).apply {
            text = if (row.granted) "已授权" else "去授权"
            textSize = 12f
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (row.granted) R.color.brand_teal_dark else R.color.text_tertiary
                )
            )
            setBackgroundResource(R.drawable.bg_perm_chip)
        }

        container.addView(title)
        container.addView(state)
        container.setOnClickListener { row.action() }
        b.permContainer.addView(container)
    }

    private fun hasPermission(perm: String): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return true
        if ((perm == Manifest.permission.BLUETOOTH_CONNECT || perm == Manifest.permission.BLUETOOTH_SCAN)
            && Build.VERSION.SDK_INT < 31
        ) return true
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMS)
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            toast("无法打开悬浮窗权限设置")
        }
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (t: Throwable) {
            false
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("无法打开电池优化设置")
            }
        }
    }

    private fun openAutoStartSettings() {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        )
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn))
                return
            } catch (t: Throwable) {
                // 继续尝试
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        } catch (t: Throwable) {
            toast("请手动在系统设置中开启自启动")
        }
    }

    // ------------------------------------------------------------------ Shizuku

    private fun setupShizuku() {
        try {
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
            // Stellar 未安装或未实现 Shizuku 协议
        }
    }

    private fun runOneKeyGrant() {
        if (!AdbShell.binderAlive()) {
            toast("未检测到 Stellar / Shizuku 服务，请先打开 Stellar 并启动服务")
            refreshPermissions()
            try {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                val i2 = packageManager.getLaunchIntentForPackage("com.stellar.adb")
                val target = i ?: i2
                if (target != null) startActivity(target)
                else toast("未安装 Stellar / Shizuku")
            } catch (t: Throwable) {
                toast("未安装 Stellar / Shizuku")
            }
            return
        }
        if (!AdbShell.hasPermission()) {
            AdbShell.requestPermission(REQ_SHIZUKU)
            return
        }
        val pkg = packageName
        toast("正在执行一键授权…")
        Thread {
            val results = AdbShell.execAll(AdbShell.buildGrantCommands(pkg))
            val okCount = results.count { it.ok }
            runOnUiThread {
                toast("一键授权完成：$okCount/${results.size} 项成功")
                refreshPermissions()
            }
        }.start()
    }

    private fun copyAdbScript() {
        val script = AdbShell.buildGrantScript(packageName)
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bt-popup-adb", script))
            toast("ADB 授权命令已复制到剪贴板")
        } catch (t: Throwable) {
            toast("复制失败")
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshServiceStatus() {
        val running = BluetoothMonitorService.running
        b.tvServiceStatus.text = if (running) "监听服务运行中" else "监听服务未启动"
        val color = if (running) ContextCompat.getColor(this, R.color.brand_teal)
        else ContextCompat.getColor(this, R.color.status_off)
        b.dotStatus.setTextColor(color)
    }

    private fun refreshLiveStatus() {
        val info = BatteryRepository.all().maxByOrNull { it.updatedAt }
        if (info == null) {
            b.tvLiveDevice.text = "当前未连接蓝牙音频设备"
            b.tvLiveLeft.text = "--%"
            b.tvLiveRight.text = "--%"
            b.tvLiveCase.text = "--%"
            b.tvBatterySource.text = "数据来源：等待耳机连接"
            return
        }
        b.tvLiveDevice.text = info.name + "\n" + info.address
        b.tvLiveLeft.text = percent(info.left)
        b.tvLiveRight.text = percent(info.right)
        b.tvLiveCase.text = percent(info.case)
        val extra = if (!info.hasSplit && info.overall >= 0) "（系统仅上报整体电量 ${info.overall}%）" else ""
        b.tvBatterySource.text = "数据来源：" + info.source.ifBlank { "未知" } + extra
    }

    private fun percent(v: Int): String = if (v in 0..100) "$v%" else "--%"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            refreshPermissions()
        }
    }
}
