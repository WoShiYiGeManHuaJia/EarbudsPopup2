package com.woshiyigemanhuajia.btpopup.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import coil.load
import com.woshiyigemanhuajia.btpopup.R
import com.woshiyigemanhuajia.btpopup.battery.BatteryInfo
import com.woshiyigemanhuajia.btpopup.util.Prefs

/**
 * 系统级悬浮弹窗（TYPE_APPLICATION_OVERLAY）。
 * 支持：圆角、自定义图片/GIF、尺寸与位置调节、出入场动画、横竖屏两套布局。
 */
object PopupOverlayManager {

    private const val TAG = "PopupOverlay"

    private val main = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var rootWindowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dismissTask: Runnable? = null
    private var currentAddress: String? = null
    private var lastShowAt = 0L

    /** 外观参数签名：参数一旦变化即重建弹窗，保证改完参数预览立刻生效 */
    private var lastSignature: String? = null

    fun isShowing(): Boolean = rootView != null

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, info: BatteryInfo, imageUri: String?) {
        if (!canDrawOverlay(context)) {
            Log.w(TAG, "缺少悬浮窗权限，无法弹窗")
            return
        }
        main.post { showInternal(context.applicationContext, info, imageUri) }
    }

    fun update(info: BatteryInfo) {
        main.post {
            val v = rootView ?: return@post
            if (currentAddress != null && !currentAddress.equals(info.address, ignoreCase = true)) return@post
            bindData(v, info)
        }
    }

    fun dismiss() {
        main.post { removeInternal(true) }
    }

    // ------------------------------------------------------------------

    private fun showInternal(context: Context, info: BatteryInfo, imageUri: String?) {
        val now = System.currentTimeMillis()
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val signature = buildSignature(info, imageUri, landscape)

        // 外观参数完全一致时才复用现有弹窗，否则重建，保证改完参数预览立即生效
        if (rootView != null && signature == lastSignature) {
            bindData(rootView!!, info)
            restartDismissTimer()
            return
        }
        if (rootView == null && now - lastShowAt < 600) {
            // 极短时间内重复事件，忽略
            return
        }

        removeInternal(false)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val layoutId = if (landscape) R.layout.popup_overlay_landscape else R.layout.popup_overlay_portrait
        val view = LayoutInflater.from(context).inflate(layoutId, null)

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val marginPx = if (landscape) (Prefs.landMarginDp * density).toInt() else (8 * density).toInt()
        val ratio = if (landscape) Prefs.landWidthPercent else Prefs.widthPercent
        val minW = (200 * density).toInt().coerceAtMost(screenW)
        val maxW = (screenW - marginPx * 2).coerceAtLeast(minW)
        val widthPx = (screenW * ratio / 100f).toInt().coerceIn(minW, maxW)

        val fixedHeight = if (landscape) Prefs.landHeightFixed else Prefs.heightFixed
        val heightDp = if (landscape) Prefs.landHeightDp else Prefs.heightDp
        val fixedHeightPx = (heightDp * density).toInt().coerceAtLeast(minW / 2)

        // 先套用最终样式（含图片区尺寸），再测量，避免"拉宽/拉高后出现空白"
        bindData(view, info)
        applyImage(context, view, imageUri)
        applyPanelStyle(view, landscape, fixedHeight)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = if (fixedHeight) {
            View.MeasureSpec.makeMeasureSpec(fixedHeightPx, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        }
        view.measure(widthSpec, heightSpec)

        val minH = (96 * density).toInt().coerceAtMost(screenH)
        val measuredH = view.measuredHeight.coerceIn(minH, screenH)
        val finalH = if (fixedHeight) fixedHeightPx else measuredH

        var x = (screenW * Prefs.posXPercent / 100f - widthPx / 2f).toInt()
        var y = (screenH * Prefs.posYPercent / 100f - finalH / 2f).toInt()
        x = x.coerceIn(marginPx, (screenW - widthPx - marginPx).coerceAtLeast(marginPx))
        y = y.coerceIn(marginPx, (screenH - finalH - marginPx).coerceAtLeast(marginPx))

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val lp = WindowManager.LayoutParams(
            widthPx,
            if (fixedHeight) finalH else WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y

        view.setOnClickListener { dismiss() }

        try {
            wm.addView(view, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "addView 失败: " + t.message)
            return
        }

        rootView = view
        rootWindowManager = wm
        layoutParams = lp
        currentAddress = info.address
        lastShowAt = now
        lastSignature = signature

        playEnter(view)
        restartDismissTimer()
    }

    /** 外观参数签名：任一参数变化都触发重建，避免"改了参数但预览没变化" */
    private fun buildSignature(info: BatteryInfo, imageUri: String?, landscape: Boolean): String {
        return listOf(
            info.address, imageUri ?: "", landscape.toString(),
            Prefs.widthPercent, Prefs.heightFixed, Prefs.heightDp,
            Prefs.landWidthPercent, Prefs.landHeightFixed, Prefs.landHeightDp, Prefs.landMarginDp,
            Prefs.posXPercent, Prefs.posYPercent, Prefs.cornerRadiusDp,
            Prefs.panelAlpha, Prefs.panelColor, Prefs.textColor, Prefs.accentColor,
            Prefs.imageHeightDp, Prefs.imageScaleMode, Prefs.animType, Prefs.animDuration
        ).joinToString("|")
    }

    private fun removeInternal(animate: Boolean) {
        val v = rootView ?: return
        val wm = rootWindowManager
        cancelDismissTimer()
        rootView = null
        rootWindowManager = null
        layoutParams = null
        currentAddress = null
        lastSignature = null

        if (!animate) {
            forceRemove(v, wm)
            return
        }

        var finished = false
        val finish = Runnable {
            if (finished) return@Runnable
            finished = true
            forceRemove(v, wm)
        }
        val duration = (Prefs.animDuration.toLong().coerceIn(80L, 1500L)) * 3 / 4
        try {
            v.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { main.post(finish) }
                .start()
        } catch (t: Throwable) {
            Log.w(TAG, "退场动画失败: " + t.message)
            finish.run()
            return
        }
        // 兜底：动画回调一旦失效（被取消 / 窗口状态异常），超时后也强制摘除窗口，
        // 避免残留一层无法点击的透明层，导致必须去关悬浮窗权限才能恢复
        main.postDelayed(finish, duration + 350L)
    }

    /** 幂等地把窗口从 WindowManager 上摘除，任何情况下都保证不留残影 */
    private fun forceRemove(view: View, wm: WindowManager?) {
        try {
            view.animate().cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "取消动画失败: " + t.message)
        }
        try {
            val manager = wm ?: (view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            if (manager != null && (view.parent != null || view.isAttachedToWindow)) {
                manager.removeViewImmediate(view)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "removeView 失败: " + t.message)
        }
    }

    private fun restartDismissTimer() {
        cancelDismissTimer()
        val delay = Prefs.dismissDelayMs
        if (delay <= 0) return
        val task = Runnable { removeInternal(true) }
        dismissTask = task
        main.postDelayed(task, delay.toLong().coerceIn(1000L, 120_000L))
    }

    private fun cancelDismissTimer() {
        dismissTask?.let { main.removeCallbacks(it) }
        dismissTask = null
    }

    // ------------------------------------------------------------------ 数据绑定

    private fun bindData(view: View, info: BatteryInfo) {
        view.findViewById<TextView>(R.id.tvDeviceName)?.text = info.name.ifBlank { BatteryInfo.UNKNOWN_NAME }
        view.findViewById<TextView>(R.id.tvConnState)?.text = buildStateText(info)

        val tag = view.findViewById<TextView>(R.id.tvSourceTag)
        tag?.text = when {
            info.source.contains("GATT") -> "GATT"
            info.source.contains("HFP") -> "HFP"
            info.source.isNotBlank() -> "SYS"
            else -> "SYS"
        }

        bindCell(view, "Left", info.left)
        bindCell(view, "Right", info.right)
        bindCell(view, "Case", info.case)
    }

    private fun bindCell(view: View, prefix: String, value: Int) {
        val labelId = view.resources.getIdentifier("tv" + prefix + "Label", "id", view.context.packageName)
        val percentId = view.resources.getIdentifier("tv" + prefix + "Percent", "id", view.context.packageName)
        val barId = view.resources.getIdentifier("pb" + prefix, "id", view.context.packageName)

        val percent = view.findViewById<TextView>(percentId)
        val bar = view.findViewById<ProgressBar>(barId)
        if (value in 0..100) {
            percent?.text = "$value%"
            bar?.progress = value
        } else {
            percent?.text = "--%"
            bar?.progress = 0
        }
        view.findViewById<TextView>(labelId)?.let { /* label 文案由布局固定 */ }
    }

    private fun buildStateText(info: BatteryInfo): String {
        val parts = mutableListOf<String>()
        parts += if (info.charging) "已连接 · 充电中" else "已连接"
        if (!info.hasSplit && info.overall >= 0) {
            parts += "整体电量 " + info.overall + "%"
        }
        if (!info.hasAny) {
            parts += "电量读取中…"
        }
        return parts.joinToString(" · ")
    }

    private fun applyImage(context: Context, view: View, imageUri: String?) {
        val image = view.findViewById<ImageView>(R.id.popupImage) ?: return
        val hint = view.findViewById<View>(R.id.noImageHint)

        val density = context.resources.displayMetrics.density
        val radiusPx = Prefs.cornerRadiusDp * density * 0.72f
        image.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        image.clipToOutline = true
        // 图片 / GIF 缩放模式可调，解决"只能调一点点大小"的观感问题
        image.scaleType = when (Prefs.imageScaleMode) {
            "fit" -> ImageView.ScaleType.FIT_CENTER
            "stretch" -> ImageView.ScaleType.FIT_XY
            "center" -> ImageView.ScaleType.CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }

        if (imageUri.isNullOrBlank()) {
            hint?.visibility = View.VISIBLE
            image.setImageDrawable(null)
            return
        }
        hint?.visibility = View.GONE
        try {
            image.load(android.net.Uri.parse(imageUri)) {
                crossfade(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "图片加载失败: " + t.message)
            hint?.visibility = View.VISIBLE
        }
    }

    private fun applyPanelStyle(view: View, landscape: Boolean, fixedHeight: Boolean) {
        val density = view.context.resources.displayMetrics.density

        // 面板背景：颜色 + 圆角 + 不透明度（颜色从此可自定义）
        val bg = view.background?.mutate() as? GradientDrawable
        if (bg != null) {
            bg.cornerRadius = Prefs.cornerRadiusDp.coerceIn(0, 200) * density
            val alpha = (Prefs.panelAlpha.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
            bg.setColor(ColorUtils.setAlphaComponent(Prefs.panelColor or (0xFF shl 24), alpha))
            view.background = bg
        }

        // 图片区尺寸：高度按 dp 精确生效，不再被旧公式压缩成几十 dp
        val wrap = view.findViewById<View>(R.id.imageWrap)
        (wrap?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val targetDp = Prefs.imageHeightDp.coerceIn(40, 600)
            when {
                landscape -> {
                    val h = (targetDp * 0.55f).toInt().coerceIn(40, 220)
                    lp.width = (h * 1.5f * density).toInt()
                    lp.height = (h * density).toInt()
                    lp.weight = 0f
                }
                fixedHeight -> {
                    // 固定高度时让图片区吃掉剩余空间，杜绝底部莫名空白
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = 0
                    lp.weight = 1f
                }
                else -> {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = (targetDp * density).toInt()
                    lp.weight = 0f
                }
            }
            wrap.layoutParams = lp
        }

        applyColors(view)
    }

    // ------------------------------------------------------------------ 动画

    private fun playEnter(view: View) {
        val duration = Prefs.animDuration.toLong().coerceIn(80L, 1500L)
        val density = view.context.resources.displayMetrics.density
        val offset = 90 * density

        when (Prefs.animType) {
            "fade" -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            "scale" -> {
                view.alpha = 0f
                view.scaleX = 0.85f
                view.scaleY = 0.85f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            "slide_top" -> {
                view.alpha = 0f
                view.translationY = -offset
                view.animate().alpha(1f).translationY(0f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            "slide_bottom" -> {
                view.alpha = 0f
                view.translationY = offset
                view.animate().alpha(1f).translationY(0f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            else -> {
                view.alpha = 0f
                view.scaleX = 0.9f
                view.scaleY = 0.9f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
            }
        }
    }

    // ------------------------------------------------------------------ 配色

    private fun applyColors(view: View) {
        val text = Prefs.textColor or (0xFF shl 24)
        val accent = Prefs.accentColor or (0xFF shl 24)

        view.findViewById<TextView>(R.id.tvDeviceName)?.setTextColor(text)
        view.findViewById<TextView>(R.id.tvConnState)?.setTextColor(ColorUtils.setAlphaComponent(text, 180))
        view.findViewById<TextView>(R.id.tvSourceTag)?.setTextColor(accent)

        intArrayOf(R.id.tvLeftLabel, R.id.tvRightLabel, R.id.tvCaseLabel).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(ColorUtils.setAlphaComponent(text, 190))
        }
        intArrayOf(R.id.tvLeftPercent, R.id.tvRightPercent, R.id.tvCasePercent).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(accent)
        }
        intArrayOf(R.id.pbLeft, R.id.pbRight, R.id.pbCase).forEach { id ->
            view.findViewById<ProgressBar>(id)?.let { paintProgressBar(it, accent) }
        }

        // 未设置图片 / GIF 时的占位提示
        (view.findViewById<View>(R.id.noImageHint) as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                when (val child = group.getChildAt(i)) {
                    is TextView -> child.setTextColor(ColorUtils.setAlphaComponent(text, 140))
                    is ImageView -> child.alpha = 0.62f
                }
            }
        }
    }

    /** 用自定义强调色重建进度条前景，避免直接 tint 把轨道一起染色 */
    private fun paintProgressBar(bar: ProgressBar, accent: Int) {
        try {
            val track = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 51))
            }
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(accent, ColorUtils.setAlphaComponent(accent, 165))
            }
            val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
            val layer = LayerDrawable(arrayOf(track, clip))
            layer.setId(0, android.R.id.background)
            layer.setId(1, android.R.id.progress)
            bar.progressDrawable = layer
            bar.progressTintList = null
        } catch (t: Throwable) {
            Log.w(TAG, "进度条染色失败: " + t.message)
        }
    }
}
