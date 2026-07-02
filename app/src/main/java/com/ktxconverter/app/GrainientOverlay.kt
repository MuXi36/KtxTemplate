package com.ktxconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * React Bits Grainient 移植：噪点纹理叠加层
 * 生成静态噪点 Bitmap，以极低 alpha 叠加在背景上，模拟胶片颗粒感
 */
class GrainientOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var grainBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 18 // 非常淡
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // 降低分辨率生成噪点，节省性能
            val scale = 4
            val gw = w / scale
            val gh = h / scale
            grainBitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888).also { bmp ->
                val rng = Random(99)
                val pixels = IntArray(gw * gh)
                for (i in pixels.indices) {
                    val g = rng.nextInt(256)
                    pixels[i] = Color.argb(255, g, g, g)
                }
                bmp.setPixels(pixels, 0, gw, 0, 0, gw, gh)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        grainBitmap?.let {
            canvas.drawBitmap(it, null,
                android.graphics.Rect(0, 0, width, height), paint)
        }
    }
}