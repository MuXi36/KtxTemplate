package com.ktxconverter.app

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/**
 * React Bits Glare Hover 移植：按钮按下时对角线光泽扫过
 * 白色光晕以 -45° 角扫过按钮表面，模拟玻璃反光
 */
object GlareSweepEffect {

    /**
     * 为按钮绑定光泽扫过效果
     * @param view 目标按钮
     * @param glareColor 光泽颜色，默认白色
     * @param glareAlpha 光泽最大透明度 (0-1)，默认 0.30
     * @param durationMs 扫过持续时间，默认 800ms
     */
    fun applyGlare(
        view: View,
        glareColor: Int = Color.WHITE,
        glareAlpha: Float = 0.30f,
        durationMs: Long = 800L
    ) {
        var glareOverlay: GlareDrawable? = null

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    glareOverlay?.let { v.overlay.remove(it) }
                    val glare = GlareDrawable(glareColor, glareAlpha)
                    glare.setBounds(0, 0, v.width, v.height)
                    v.overlay.add(glare)
                    glareOverlay = glare
                    glare.startSweep(durationMs) {
                        v.overlay.remove(glare)
                        if (glareOverlay === glare) glareOverlay = null
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 光泽会在动画完成后自动移除
                }
            }
            false // 不消费事件，让按钮正常响应点击
        }
    }
}

/**
 * 对角线光泽 Drawable
 * 绘制一条白色半透明带，从左上角扫到右下角（-45°）
 */
class GlareDrawable(
    private val glareColor: Int = Color.WHITE,
    private val maxAlpha: Float = 0.30f
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweepPosition = 0f // 0=左上角外 → 1.5=右下角外
    private var animator: ValueAnimator? = null

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return

        val diagonal = kotlin.math.hypot(w, h)
        val dx = sweepPosition * diagonal
        val dy = sweepPosition * diagonal

        // 光泽带宽度为对角线的 60%
        val bandWidth = diagonal * 0.6f

        val alpha = when {
            sweepPosition < 0f -> 0
            sweepPosition > 1.2f -> (maxAlpha * ((1.5f - sweepPosition) / 0.3f).coerceIn(0f, 1f)).toInt()
            else -> (maxAlpha * 255).toInt()
        }

        val startColor = Color.argb(0, Color.red(glareColor), Color.green(glareColor), Color.blue(glareColor))
        val midColor = Color.argb(alpha, Color.red(glareColor), Color.green(glareColor), Color.blue(glareColor))
        val endColor = Color.argb(0, Color.red(glareColor), Color.green(glareColor), Color.blue(glareColor))

        paint.shader = LinearGradient(
            dx - bandWidth, dy - bandWidth,
            dx + bandWidth, dy + bandWidth,
            intArrayOf(startColor, midColor, midColor, endColor),
            floatArrayOf(0f, 0.35f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawRect(bounds, paint)
    }

    fun startSweep(durationMs: Long, onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(-0.3f, 1.5f).apply {
            this.duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                sweepPosition = anim.animatedValue as Float
                invalidateSelf()
            }
            doOnEnd {
                onEnd()
            }
            start()
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(animation: android.animation.Animator) { action() }
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
    }
}
