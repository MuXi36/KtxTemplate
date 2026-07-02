package com.ktxconverter.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 光遇风格星空粒子覆盖层 — 混合原生辉点 + 游戏装饰图案
 */
class StardustOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val baseAlpha: Float,
        val phase: Float,
        val speed: Float,
        val color: Int,
        val type: Int = 0,  // 0=glow dot, 1/2/3=deco bitmap
        val rotation: Float = 0f
    )

    private val stars = mutableListOf<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var width = 0f
    private var height = 0f

    /** 光遇装饰图案（从 drawable 加载） */
    private val decoBitmaps: Array<Bitmap?> = arrayOfNulls(3)

    /** 星点颜色调色板 — 光遇暖色调 */
    private val palette = intArrayOf(
        Color.argb(255, 255, 220, 150), // 暖金
        Color.argb(255, 244, 169, 80),  // 琥珀
        Color.argb(255, 255, 255, 220), // 暖白
        Color.argb(255, 180, 210, 240), // 淡蓝
        Color.argb(255, 200, 180, 240), // 淡紫
    )

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // 不拦截触摸，让下层可点击
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            width = w.toFloat()
            height = h.toFloat()
            generateStars()
        }
    }

    private fun generateStars() {
        stars.clear()
        // 懒加载装饰图案
        if (decoBitmaps[0] == null) {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            decoBitmaps[0] = BitmapFactory.decodeResource(resources, R.drawable.sky_deco1, opts)
            decoBitmaps[1] = BitmapFactory.decodeResource(resources, R.drawable.sky_deco2, opts)
            decoBitmaps[2] = BitmapFactory.decodeResource(resources, R.drawable.sky_deco3, opts)
        }
        val rng = Random(42)
        val count = (width * height / 6000).toInt().coerceIn(40, 100)
        for (i in 0 until count) {
            val x = rng.nextFloat() * width
            val y = rng.nextFloat() * height
            // 10% 概率使用光遇装饰图案
            val type = if (rng.nextInt(10) == 0) rng.nextInt(3) + 1 else 0
            val radius = when (type) {
                0 -> rng.nextFloat() * 2.5f + 0.5f
                else -> rng.nextFloat() * 8f + 8f  // 图案稍大
            }
            val alpha = rng.nextFloat() * 0.35f + 0.1f
            val phase = rng.nextFloat() * Math.PI.toFloat() * 2
            val speed = rng.nextFloat() * 0.5f + 0.2f
            val color = palette[rng.nextInt(palette.size)]
            val rotation = rng.nextFloat() * 360f
            stars.add(Star(x, y, radius, alpha, phase, speed, color, type, rotation))
        }
    }

    fun startAnimation() {
        stopAnimation()
        animator = ValueAnimator.ofFloat(0f, Math.PI.toFloat() * 2).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = null // 线性
            addUpdateListener { _ ->
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private var time = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        time += 0.04f
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (star in stars) {
            val flicker = (sin(time * star.speed + star.phase) * 0.5f + 0.5f)
            val alpha = (star.baseAlpha * (0.3f + flicker * 0.7f) * 255).toInt().coerceIn(0, 255)

            if (star.type == 0) {
                // 原生辉点
                paint.shader = RadialGradient(
                    star.x, star.y, star.radius * 3,
                    intArrayOf(
                        (star.color and 0x00FFFFFF) or (alpha shl 24),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.alpha = alpha
                canvas.drawCircle(star.x, star.y, star.radius, paint)
            } else {
                // 光遇装饰图案
                val bm = decoBitmaps[star.type - 1] ?: continue
                bmpPaint.alpha = alpha
                val scaledW = star.radius * 2
                val scaledH = scaledW * bm.height / bm.width
                canvas.save()
                canvas.rotate(star.rotation, star.x, star.y)
                canvas.drawBitmap(bm, null,
                    android.graphics.RectF(star.x - scaledW/2, star.y - scaledH/2,
                        star.x + scaledW/2, star.y + scaledH/2), bmpPaint)
                canvas.restore()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}