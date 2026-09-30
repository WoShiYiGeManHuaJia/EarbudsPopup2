package com.woshiyigemanhuajia.btpopup.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置存储。
 */
object Prefs {

    private const val NAME = "bt_popup_prefs"
    private lateinit var sp: SharedPreferences
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        ready = true
    }

    private fun gs(k: String, d: String?): String? = if (ready) sp.getString(k, d) else d
    private fun ss(k: String, v: String?) {
        if (ready) sp.edit().putString(k, v).apply()
    }

    private fun gi(k: String, d: Int): Int = if (ready) sp.getInt(k, d) else d
    private fun si(k: String, v: Int) {
        if (ready) sp.edit().putInt(k, v).apply()
    }

    private fun gb(k: String, d: Boolean): Boolean = if (ready) sp.getBoolean(k, d) else d
    private fun sb(k: String, v: Boolean) {
        if (ready) sp.edit().putBoolean(k, v).apply()
    }

    // ---------------- 外观 ----------------
    var imageUri: String?
        get() = gs("image_uri", null)
        set(v) = ss("image_uri", v)

    /** 0-100，面板背景不透明度 */
    var panelAlpha: Int
        get() = gi("panel_alpha", 88)
        set(v) = si("panel_alpha", v)

    /** 图片区高度 dp */
    var imageHeightDp: Int
        get() = gi("image_height", 176)
        set(v) = si("image_height", v)

    var autoPopup: Boolean
        get() = gb("auto_popup", true)
        set(v) = sb("auto_popup", v)

    // ---------------- 竖屏尺寸 / 位置 ----------------
    /** 弹窗宽度占屏幕宽度百分比 */
    var widthPercent: Int
        get() = gi("width_percent", 84)
        set(v) = si("width_percent", v)

    /** 0 = 高度自适应内容，1 = 固定高度 */
    var heightFixed: Boolean
        get() = gb("height_fixed", false)
        set(v) = sb("height_fixed", v)

    var heightDp: Int
        get() = gi("height_dp", 300)
        set(v) = si("height_dp", v)

    /** 弹窗中心水平位置 0-100（相对屏幕宽） */
    var posXPercent: Int
        get() = gi("pos_x", 50)
        set(v) = si("pos_x", 50)

    /** 弹窗中心垂直位置 0-100（相对屏幕高） */
    var posYPercent: Int
        get() = gi("pos_y", 50)
        set(v) = si("pos_y", 50)

    var cornerRadiusDp: Int
        get() = gi("corner_radius", 28)
        set(v) = si("corner_radius", 28)

    // ---------------- 横屏 ----------------
    var landWidthPercent: Int
        get() = gi("land_width_percent", 64)
        set(v) = si("land_width_percent", 64)

    var landHeightFixed: Boolean
        get() = gb("land_height_fixed", false)
        set(v) = sb("land_height_fixed", false)

    var landHeightDp: Int
        get() = gi("land_height_dp", 120)
        set(v) = si("land_height_dp", 120)

    var landMarginDp: Int
        get() = gi("land_margin", 28)
        set(v) = si("land_margin", 28)

    // ---------------- 动画 ----------------
    /** fade / scale / slide_top / slide_bottom / spring */
    var animType: String
        get() = gs("anim_type", "spring") ?: "spring"
        set(v) = ss("anim_type", v)

    var animDuration: Int
        get() = gi("anim_duration", 320)
        set(v) = si("anim_duration", 320)

    /** 自动关闭延迟 ms，0 表示不自动关闭 */
    var dismissDelayMs: Int
        get() = gi("dismiss_delay", 4000)
        set(v) = si("dismiss_delay", 4000)

    // ---------------- 保活 ----------------
    var foregroundGuard: Boolean
        get() = gb("foreground_guard", true)
        set(v) = sb("foreground_guard", v)

    var silentNotification: Boolean
        get() = gb("silent_notif", true)
        set(v) = sb("silent_notif", v)

    var bootStart: Boolean
        get() = gb("boot_start", true)
        set(v) = sb("boot_start", v)

    var monitorEnabled: Boolean
        get() = gb("monitor_enabled", true)
        set(v) = sb("monitor_enabled", v)

    fun all(): Map<String, *> = if (ready) sp.all else emptyMap<String, Any>()
}
