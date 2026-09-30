package com.woshiyigemanhuajia.btpopup.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * 轻量 HSV 调色盘：色相 / 饱和度 / 明度三条滑块 + 实时预览 + 十六进制值。
 * 不依赖任何第三方取色库，避免额外构建风险。
 */
class ColorPickerDialog(
    context: Context,
    private val dialogTitle: String,
    private val initialColor: Int,
    private val onPicked: (Int) -> Unit
) : Dialog(context) {

    private val hsv = FloatArray(3)
    private lateinit var preview: View
    private lateinit var hexLabel: TextView

    init {
        Color.colorToHSV(initialColor, hsv)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(0xFFF8FAFC.toInt())
        }

        root.addView(
            TextView(context).apply {
                text = dialogTitle
                textSize = 16f
                setTextColor(0xFF0F172A.toInt())
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        preview = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(initialColor)
            }
        }
        root.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(14) }
        )

        hexLabel = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
        }
        root.addView(
            hexLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        addSlider(root, "色相", (hsv[0] / 360f * 255f).toInt()) {
            hsv[0] = it / 255f * 360f
            refresh()
        }
        addSlider(root, "饱和度", (hsv[1] * 255f).toInt()) {
            hsv[1] = it / 255f
            refresh()
        }
        addSlider(root, "明度", (hsv[2] * 255f).toInt()) {
            hsv[2] = it / 255f
            refresh()
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            MaterialButton(context).apply {
                text = "取消"
                setOnClickListener { dismiss() }
            }
        )
        buttons.addView(
            MaterialButton(context).apply {
                text = "确定"
                setOnClickListener {
                    onPicked(currentColor())
                    dismiss()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        setContentView(root)
        refresh()
    }

    private fun addSlider(parent: LinearLayout, labelText: String, initial: Int, onChange: (Int) -> Unit) {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(
            TextView(context).apply {
                text = labelText
                textSize = 12f
                setTextColor(0xFF475569.toInt())
            }
        )

        val bar = SeekBar(context).apply { max = 255 }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        row.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        parent.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        bar.progress = initial.coerceIn(0, 255)
    }

    private fun currentColor(): Int = Color.HSVToColor(hsv)

    private fun refresh() {
        val color = currentColor()
        (preview.background?.mutate() as? GradientDrawable)?.let {
            it.setColor(color)
            preview.background = it
        }
        hexLabel.text = String.format("#%06X", 0xFFFFFF and color)
    }
}
