package com.woshiyigemanhuajia.btpopup.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
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
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dismissTask: Runnable? = null
    private var currentAddress: String? = null
    private var lastShowAt = 0L

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
        val sameDevice = currentAddress != null && currentAddress.equals(info.address, ignoreCase = true)

        if (rootView != null && sameDevice) {
            bindData(rootView!!, info)
            restartDismissTimer()
            return
        }
        if (now - lastShowAt < 1200) {
            // 极短时间内重复事件，直接刷新
            rootView?.let { bindData(it, info); restartDismissTimer() }
            return
        }

        removeInternal(false)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val layoutId = if (landscape) R.layout.popup_overlay_landscape else R.layout.popup_overlay_portrait
        val view = LayoutInflater.from(context).inflate(layoutId, null)

        bindData(view, info)
        applyImage(context, view, imageUri)
        applyPanelStyle(view)

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val marginPx = if (landscape) (Prefs.landMarginDp * density).toInt() else (8 * density).toInt()
        val ratio = if (landscape) Prefs.landWidthPercent else Prefs.widthPercent
        var widthPx = (screenW * ratio / 100f).toInt()
        widthPx = widthPx.coerceAtMost(screenW - marginPx * 2).coerceAtLeast((220 * density).toInt())

        val fixedHeight = if (landscape) Prefs.landHeightFixed else Prefs.heightFixed
        val heightDp = if (landscape) Prefs.landHeightDp else Prefs.heightDp

        // 预测量，便于计算居中位置
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = if (fixedHeight) {
            View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        }
        view.measure(widthSpec, heightSpec)
        val measuredH = view.measuredHeight.coerceAtLeast((72 * density).toInt())
        val finalH = if (fixedHeight) (heightDp * density).toInt() else measuredH

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
        windowManager = wm
        layoutParams = lp
        currentAddress = info.address
        lastShowAt = now

        playEnter(view)
        restartDismissTimer()
    }

    private fun removeInternal(animate: Boolean) {
        val v = rootView ?: return
        cancelDismissTimer()
        rootView = null
        currentAddress = null
        layoutParams = null
        playExit(v, animate) {
            try {
                windowManager?.removeView(v)
            } catch (t: Throwable) {
                Log.w(TAG, "removeView 失败: " + t.message)
            }
            windowManager = null
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

    private fun applyPanelStyle(view: View) {
        val density = view.context.resources.displayMetrics.density
        val radiusPx = Prefs.cornerRadiusDp * density
        val bg = view.background?.mutate() as? GradientDrawable
        if (bg != null) {
            bg.cornerRadius = radiusPx
            val cs = bg.color
            if (cs != null) {
                val alpha = (Prefs.panelAlpha.coerceIn(10, 100) * 255 / 100).coerceIn(26, 255)
                bg.setColor(ColorUtils.setAlphaComponent(cs.defaultColor, alpha))
            }
            view.background = bg
        }

        view.findViewById<View>(R.id.imageWrap)?.let { wrap ->
            val ratio = Prefs.imageHeightDp / 100f
            val lp = wrap.layoutParams
            if (lp != null) {
                val density2 = view.context.resources.displayMetrics.density
                lp.height = if (view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    (76 * density2).toInt()
                } else {
                    (140 * ratio).toInt().coerceIn((80 * density2).toInt(), (280 * density2).toInt())
                }
                wrap.layoutParams = lp
            }
        }
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

    private fun playExit(view: View, animate: Boolean, end: () -> Unit) {
        if (!animate) {
            end()
            return
        }
        val duration = (Prefs.animDuration.toLong().coerceIn(80L, 1500L)) * 3 / 4
        view.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { end() }
            .start()
    }
}
