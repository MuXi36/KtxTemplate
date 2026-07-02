package com.ktxconverter.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * React Bits Aurora 移植：动态极光背景
 * 多层 RadialGradient 色块按正弦波周期性移动，模拟极光流动
 */
class AuroraBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class AuroraBlob(
        var cx: Float, var cy: Float,
        val radius: Float,
        val phaseX: Float,
        val phaseY: Float,
        val speedX: Float,
        val speedY: Float,
        val color: Int
    )

    private val blobs = mutableListOf<AuroraBlob>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var time = 0f
    private var w = 0f
    private var h = 0f

    // 光遇暖色系极光色（半透明）
    private val auroraColors = intArrayOf(
        Color.argb(90, 232, 164, 73),   // 琥珀
        Color.argb(55, 244, 169, 80),   // 金
        Color.argb(70, 255, 140, 66),   // 暖橙
        Color.argb(40, 126, 200, 227),  // 天蓝
        Color.argb(60, 255, 160, 100),  // 桃
    )

    init {
        setBackgroundColor(Color.parseColor("#FFFAF5")) // sky_cloud 底色
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(width, height, oldw, oldh)
        if (width > 0 && height > 0) {
            w = width.toFloat()
            h = height.toFloat()
            generateBlobs()
            startAnimation()
        }
    }

    private fun generateBlobs() {
        blobs.clear()
        val rng = java.util.Random(7)
        for (i in 0..4) {
            blobs.add(AuroraBlob(
                cx = rng.nextFloat() * w,
                cy = rng.nextFloat() * h,
                radius = (rng.nextFloat() * 0.4f + 0.25f) * w.coerceAtMost(h),
                phaseX = rng.nextFloat() * 6.28f,
                phaseY = rng.nextFloat() * 6.28f,
                speedX = rng.nextFloat() * 0.3f + 0.2f,
                speedY = rng.nextFloat() * 0.4f + 0.3f,
                color = auroraColors[i]
            ))
        }
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 50
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener {
                time += 0.02f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (blob in blobs) {
            val x = blob.cx + sin(time * blob.speedX + blob.phaseX) * w * 0.3f
            val y = blob.cy + cos(time * blob.speedY + blob.phaseY) * h * 0.25f
            paint.shader = RadialGradient(
                x, y, blob.radius,
                intArrayOf(blob.color, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}