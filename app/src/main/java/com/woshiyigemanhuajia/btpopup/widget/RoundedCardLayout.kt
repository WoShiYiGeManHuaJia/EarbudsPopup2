package com.woshiyigemanhuajia.btpopup.widget

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * 圆角卡片容器（参考外观 RoundedCardLayout 的 Kotlin 移植）。
 *
 * 与"给根布局设一个圆角背景"的区别：
 * 1. 圆角背景只负责画，子 View（图片）铺满时仍会把四个角盖成直角；
 *    这里同时用 [ViewOutlineProvider] + clipToOutline 做「框架级裁剪」，
 *    子 View 超出圆角的部分会被直接裁掉。
 * 2. 裁剪层本身带抗锯齿，图片铺满卡片时四个角也是平滑的。
 *
 * 主要用于横屏弹窗：图片铺满整张卡片、详情信息叠在底部，圆角必须由容器保证。
 */
class RoundedCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cardRadius = 0f
    private var strokeWidth = 0f
    private var strokeColor = 0

    /**
     * 一次性设置卡片外观：圆角、底色、可选描边。
     * 可反复调用（改圆角 / 改透明度 / 改配色时即时生效）。
     */
    fun setCardStyle(radiusPx: Float, color: Int, outlineColor: Int = 0, outlineWidthPx: Float = 0f) {
        cardRadius = radiusPx.coerceAtLeast(0f)
        strokeColor = outlineColor
        strokeWidth = outlineWidthPx.coerceAtLeast(0f)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cardRadius
            setColor(color)
            if (strokeWidth > 0f && outlineColor != 0) {
                setStroke(strokeWidth.toInt().coerceAtLeast(1), outlineColor)
            }
        }
        background = bg

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (cardRadius <= 0f) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
                }
            }
        }
        clipToOutline = cardRadius > 0f
        invalidateOutline()
        invalidate()
    }

    fun getCardRadius(): Float = cardRadius

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cardRadius > 0f) invalidateOutline()
    }
}
