package com.ktxconverter.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * React Bits ShinyText 移植：流光扫过文字
 * LinearGradient shader + ValueAnimator 移动，光泽循环扫过
 */
class ShinyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var animator: ValueAnimator? = null
    private var gradientOffset = 0f

    // 光遇暖色系光泽：琥珀→金→暖橙→琥珀
    private val shineColors = intArrayOf(
        Color.parseColor("#E8A449"),
        Color.parseColor("#FFD700"),
        Color.parseColor("#FFAA50"),
        Color.parseColor("#E8A449"),
    )
    private val shinePositions = floatArrayOf(0f, 0.25f, 0.55f, 1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        startShine()
    }

    private fun startShine() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = 2800
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                gradientOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val p = paint
        val text = text?.toString() ?: ""
        if (text.isNotEmpty()) {
            val tw = p.measureText(text)
            if (tw > 0) {
                p.shader = LinearGradient(
                    gradientOffset * tw, 0f,
                    (gradientOffset + 1f) * tw, 0f,
                    shineColors, shinePositions,
                    Shader.TileMode.CLAMP
                )
            }
        }
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}