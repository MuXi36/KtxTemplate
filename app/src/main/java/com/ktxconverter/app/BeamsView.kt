package com.ktxconverter.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 光遇射线 — 24 条 conic-gradient 扇形 + 径向渐变淡出
 * 配色：金·蓝·金·紫·白·蓝 循环 4 组
 */
class BeamsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var angle = 0f
    private var cx = 0f
    private var cy = 0f

    // ── 24 条射线配色（6 条 × 4 组） ──
    // 金·蓝·金·紫·白·蓝
    private val rayColors = arrayOf(
        intArrayOf(255, 210, 130),  // 金 0°
        intArrayOf(130, 210, 235),  // 蓝 15°
        intArrayOf(255, 230, 180),  // 金 30°
        intArrayOf(190, 165, 235),  // 紫 45°
        intArrayOf(255, 245, 220),  // 白 60°
        intArrayOf(120, 195, 225)   // 蓝 75°
    )

    // 每组 alpha 微调以丰富层次
    private val rayAlphas = arrayOf(72, 61, 64, 51, 56, 59) // ~ × 0.28, 0.24, 0.25, 0.20, 0.22, 0.23

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            cx = w / 2f
            cy = h * 0.18f
            startRotation()
        }
    }

    private fun startRotation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 25000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val maxR = kotlin.math.hypot(width.toFloat(), height.toFloat())
        val fadeEdge = maxR * 0.58f
        val rayHalfWidth = 0.4f // 半宽 0.4° → 全宽 0.8°

        // 绘制区域矩形（用于 drawArc）
        val arcRect = RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR)

        for (i in 0 until 24) {
            val colorIdx = i % 6
            val (r, g, b) = rayColors[colorIdx]
            val alpha = rayAlphas[colorIdx]

            // 射线着色器：中心亮 → 边缘透明
            // ponytail: RadialGradient 三阶：全色中心 25% → 过渡 → 透明 58%
            rayPaint.shader = RadialGradient(
                cx, cy, fadeEdge,
                intArrayOf(
                    Color.argb(alpha, r, g, b),       // 中心纯色
                    Color.argb((alpha * 0.7).toInt(), r, g, b), // 过渡
                    Color.TRANSPARENT                   // 边缘消失
                ),
                floatArrayOf(0f, 0.25f, 1f),
                Shader.TileMode.CLAMP
            )

            val startAngle = i * 15f + angle - rayHalfWidth
            canvas.drawArc(arcRect, startAngle, rayHalfWidth * 2f, true, rayPaint)
        }

        // ── 中心发光点 ──
        val glowRadius = 30f * resources.displayMetrics.density
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius * 2.5f,
            intArrayOf(
                Color.argb(180, 255, 240, 200),
                Color.argb(70, 255, 180, 80),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius * 2.5f, glowPaint)

        // ── 中心高亮光晕叠加 ──
        glowPaint.shader = null
        glowPaint.color = Color.argb(30, 255, 230, 180)
        canvas.drawCircle(cx, cy, maxR * 0.12f, glowPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}