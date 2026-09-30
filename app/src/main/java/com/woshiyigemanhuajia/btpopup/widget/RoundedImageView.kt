package com.woshiyigemanhuajia.btpopup.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.abs

/**
 * 圆角图片（参考外观 RoundedImageView 的 Kotlin 移植）。
 *
 * 用离屏图层 + DST_IN 圆角遮罩做裁剪，而不是 `clipToOutline`：
 * 后者在部分机型上边缘会出现锯齿/毛刺，图片铺满整张卡片时比较明显。
 * 这里圆角边缘由 [Paint] 的 ANTI_ALIAS_FLAG 保证平滑。
 */
class RoundedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val roundRect = RectF()
    private val roundPath = Path()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskMode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    private var radiusPx = 0f

    /** 设置圆角半径（px），传入 0 表示不裁剪 */
    fun setRadius(value: Float) {
        val v = value.coerceAtLeast(0f)
        if (abs(v - radiusPx) < 0.5f) return
        radiusPx = v
        invalidate()
    }

    fun getRadius(): Float = radiusPx

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (radiusPx <= 0f || w <= 0 || h <= 0) {
            super.onDraw(canvas)
            return
        }

        roundRect.set(0f, 0f, w.toFloat(), h.toFloat())
        roundPath.rewind()
        roundPath.addRoundRect(roundRect, radiusPx, radiusPx, Path.Direction.CW)

        val layer = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        super.onDraw(canvas)
        maskPaint.xfermode = maskMode
        canvas.drawPath(roundPath, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(layer)
    }
}
