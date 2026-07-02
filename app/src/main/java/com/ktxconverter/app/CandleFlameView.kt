package com.ktxconverter.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * 光遇风格蜡烛火焰 — 源自 Yellow5A5/CandlesAnimView (51⭐)
 * Bezier 曲线火焰 + 脉动光环，颜色适配光遇琥珀色系
 */
class CandleFlameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rng = Random(42)

    // 火焰颜色：光遇琥珀→暖橙
    private var flameGradient: LinearGradient? = null
    // 光环：暖金→透明
    private var haloGradient: RadialGradient? = null

    private var flameHeight = 0f
    private var preHeight = 0f
    private var flameWidth = 0f
    private var preWidth = 0f
    private var topXOffset = 0f
    private var topYOffset = 0f
    private var isFiring = true
    private var haloRadius = 40f

    private var flameAnimator: ValueAnimator? = null
    private var haloAnimator: ValueAnimator? = null
    private var invalidateAnimator: ValueAnimator? = null

    private var centerX = 0f
    private var baseY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            centerX = w / 2f
            baseY = h.toFloat()
            preWidth = w * 0.55f
            preHeight = h * 0.72f
            flameWidth = preWidth
            flameHeight = 0f

            flameGradient = LinearGradient(
                centerX, baseY - preHeight * 0.2f,
                centerX, baseY - preHeight,
                // 光遇色：琥珀 #E8A449 → 暖橙 #FF8C42
                Color.parseColor("#E8A449"),
                Color.parseColor("#FF8C42"),
                Shader.TileMode.CLAMP
            )
            haloGradient = RadialGradient(
                centerX, baseY - preHeight * 0.4f, haloRadius,
                intArrayOf(
                    Color.parseColor("#40F4A950"),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )

            startAnimations()
        }
    }

    private fun startAnimations() {
        // 火焰燃起/熄灭周期
        flameAnimator = ValueAnimator.ofFloat(0f, 4f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                when {
                    t in 1.0f..1.2f -> {
                        val v = 1.0f - 5f * (t - 1.0f)
                        flameHeight = preHeight * (1f - v)
                        isFiring = true
                    }
                    t >= 3.5f -> {
                        val v = 2f * (t - 3.5f)
                        topXOffset = -20f * v
                        topYOffset = 160f * v
                        isFiring = false
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    topXOffset = 0f
                    topYOffset = 0f
                    flameHeight = 0f
                    flameWidth = preWidth
                    isFiring = true
                }
            })
            start()
        }

        // 光环脉动
        haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                if (isFiring) {
                    haloRadius = 40f + (t % 1.0f) * 15f
                }
            }
            start()
        }

        // 持续刷新
        invalidateAnimator = ValueAnimator.ofInt(0, 1).apply {
            duration = 16
            repeatCount = ValueAnimator.INFINITE
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (flameGradient == null || preWidth <= 0) return

        val cx = centerX
        val by = baseY

        // ---- 绘制烛身 ----
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.parseColor("#C8A882") // 暖棕蜡色
        val bodyW = preWidth * 0.35f
        val bodyH = preHeight * 0.22f
        canvas.drawRoundRect(cx - bodyW, by - bodyH, cx + bodyW, by, 6f, 6f, paint)

        // ---- 绘制火焰 ----
        paint.style = Paint.Style.FILL
        paint.shader = flameGradient
        path.reset()

        val halfW = flameWidth / 2f
        val jitter = (1f - abs(rng.nextFloat() - 0.5f) * 2f) * 4f // 微小抖动

        // 火焰底部
        path.moveTo(cx - halfW, by - bodyH)
        // 右侧曲线
        path.quadTo(
            cx + halfW * 0.6f, by - bodyH - flameHeight * 0.35f,
            cx + halfW * 0.5f, by - bodyH - flameHeight * 0.15f
        )
        // 顶部尖点（带随机偏移模拟摇曳）
        path.quadTo(
            cx + jitter + topXOffset,
            by - bodyH - flameHeight * 1.5f + topYOffset,
            cx - halfW * 0.5f,
            by - bodyH - flameHeight * 0.15f
        )
        // 左侧曲线
        path.quadTo(
            cx - halfW * 0.6f, by - bodyH - flameHeight * 0.35f,
            cx - halfW, by - bodyH
        )

        canvas.drawPath(path, paint)

        // ---- 绘制光环 ----
        if (isFiring) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.shader = haloGradient
            val hy = by - bodyH - flameHeight * 0.4f
            canvas.drawCircle(cx, hy, haloRadius, paint)
            canvas.drawCircle(cx, hy, haloRadius + 4f, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flameAnimator?.cancel()
        haloAnimator?.cancel()
        invalidateAnimator?.cancel()
    }
}