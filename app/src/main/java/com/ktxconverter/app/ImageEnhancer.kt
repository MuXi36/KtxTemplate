package com.ktxconverter.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PS精致效果后处理 — 移植自 ps_postprocess.py
 *
 * 对应PS操作步骤：
 *   1. 色阶裁切 → 去灰雾，强化黑白对比
 *   2. 单色替换 → 白→裸粉 #F2D8D8，黑→纯黑
 *   3. 高斯平滑+二值化 → 抗锯齿，锐化轮廓
 *   4. 中值滤波降噪 → 消除压缩噪点
 */
object ImageEnhancer {

    /** 裸粉色 #F2D8D8 */
    private val ICON_COLOR_R = 242
    private val ICON_COLOR_G = 216
    private val ICON_COLOR_B = 216

    /** 色阶黑场阈值 (0~255)，低于此值→纯黑 */
    private const val LEVELS_LOW = 30
    /** 色阶白场阈值，高于此值→纯色 */
    private const val LEVELS_HIGH = 220
    /** 高斯模糊半径（像素，用 box blur 近似） */
    private const val BLUR_RADIUS = 1
    /** 二值化阈值 */
    private const val BINARY_THRESH = 128

    // ──── 公开入口 ────

    /**
     * ETC2 块效应去噪 — 自适应平滑 4x4 块边界（保留用于单独调用）
     */
    fun deblock(pixels: ByteArray, width: Int, height: Int, strength: Float = 1.0f): ByteArray {
        if (strength <= 0f || width < 4 || height < 4) return pixels
        val rgb = IntArray(width * height * 3)
        for (idx in 0 until width * height) {
            rgb[idx * 3] = pixels[idx * 4].toInt() and 0xFF
            rgb[idx * 3 + 1] = pixels[idx * 4 + 1].toInt() and 0xFF
            rgb[idx * 3 + 2] = pixels[idx * 4 + 2].toInt() and 0xFF
        }
        val threshold = (6 + 30 * strength).toInt()
        // 垂直块边界
        for (bx in 4 until width step 4) {
            for (offset in intArrayOf(-2, -1, 0, 1)) {
                val x = bx + offset
                if (x < 1 || x >= width - 1) continue
                val dist = if (offset >= 0) abs(offset) + 0.5f else abs(offset) - 0.5f
                val wgt = strength * max(0f, 1f - abs(dist) / 3f)
                if (wgt <= 0.01f) continue
                for (c in 0 until 3) {
                    for (y in 0 until height) {
                        val idx = (y * width + x) * 3 + c
                        val prev = (y * width + x - 1) * 3 + c
                        val next = (y * width + x + 1) * 3 + c
                        val diff = abs(rgb[prev] - rgb[next])
                        if (diff < threshold) {
                            val newVal = rgb[idx] + (wgt * ((rgb[prev] + rgb[next]) * 0.5f - rgb[idx])).toInt()
                            rgb[idx] = newVal.coerceIn(0, 255)
                        }
                    }
                }
            }
        }
        // 水平块边界
        for (by in 4 until height step 4) {
            for (offset in intArrayOf(-2, -1, 0, 1)) {
                val y = by + offset
                if (y < 1 || y >= height - 1) continue
                val dist = if (offset >= 0) abs(offset) + 0.5f else abs(offset) - 0.5f
                val wgt = strength * max(0f, 1f - abs(dist) / 3f)
                if (wgt <= 0.01f) continue
                for (c in 0 until 3) {
                    for (x in 0 until width) {
                        val idx = (y * width + x) * 3 + c
                        val prev = ((y - 1) * width + x) * 3 + c
                        val next = ((y + 1) * width + x) * 3 + c
                        val diff = abs(rgb[prev] - rgb[next])
                        if (diff < threshold) {
                            val newVal = rgb[idx] + (wgt * ((rgb[prev] + rgb[next]) * 0.5f - rgb[idx])).toInt()
                            rgb[idx] = newVal.coerceIn(0, 255)
                        }
                    }
                }
            }
        }
        val result = ByteArray(width * height * 4)
        for (idx in 0 until width * height) {
            result[idx * 4] = rgb[idx * 3].toByte()
            result[idx * 4 + 1] = rgb[idx * 3 + 1].toByte()
            result[idx * 4 + 2] = rgb[idx * 3 + 2].toByte()
            result[idx * 4 + 3] = pixels[idx * 4 + 3]
        }
        return result
    }

    /**
     * PS精致后处理流水线（ENHANCE 颜色模式）
     *
     * 输入：LUT着色后的 RGBA 像素
     * 输出：色阶裁切 + 单色统一 + 抗锯齿 + 降噪 的精致贴图
     */
    fun enhance(pixels: ByteArray, width: Int, height: Int): ByteArray {
        val n = width * height

        // ── Pass 1: 合并 Step1+2+3（背景纯黑 + 色阶裁切 + 单色替换） ──
        // 一次遍历完成，避免3次全图扫描
        val leveled = ByteArray(n)
        for (i in 0 until n) {
            val a = pixels[i * 4 + 3].toInt() and 0xFF
            // Step 1: 背景填充纯黑
            if (a < 10) {
                pixels[i * 4] = 0; pixels[i * 4 + 1] = 0; pixels[i * 4 + 2] = 0
                pixels[i * 4 + 3] = 0
                leveled[i] = 0
                continue
            }
            // Step 2: 色阶裁切（亮度计算+裁切）
            val r = pixels[i * 4].toInt() and 0xFF
            val g = pixels[i * 4 + 1].toInt() and 0xFF
            val b = pixels[i * 4 + 2].toInt() and 0xFF
            val lum = (r + g + b) / 3
            val v = if (lum <= LEVELS_LOW) 0
                    else if (lum >= LEVELS_HIGH) 255
                    else ((lum - LEVELS_LOW) * 255 / (LEVELS_HIGH - LEVELS_LOW)).coerceIn(0, 255)
            leveled[i] = v.toByte()
            // Step 3: 单色替换（黑→纯黑，白→裸粉）
            val factor = v / 255f
            pixels[i * 4] = (ICON_COLOR_R * factor).toInt().toByte()
            pixels[i * 4 + 1] = (ICON_COLOR_G * factor).toInt().toByte()
            pixels[i * 4 + 2] = (ICON_COLOR_B * factor).toInt().toByte()
            // Alpha: 暗部透明，亮部不透明
            val newA = if (v < 10) 0 else if (v > 200) 255 else ((v - 10) * 255 / 190).coerceIn(0, 255)
            pixels[i * 4 + 3] = newA.toByte()
        }

        // ── Pass 2: Box Blur + 二值化重绘边缘（抗锯齿） ──
        val blurred = boxBlur(pixels, width, height, BLUR_RADIUS)
        for (i in 0 until n) {
            val r = blurred[i * 4].toInt() and 0xFF
            val g = blurred[i * 4 + 1].toInt() and 0xFF
            val b = blurred[i * 4 + 2].toInt() and 0xFF
            val lum = (r + g + b) / 3
            if (lum <= BINARY_THRESH) {
                pixels[i * 4] = 0; pixels[i * 4 + 1] = 0
                pixels[i * 4 + 2] = 0; pixels[i * 4 + 3] = 0
            } else {
                pixels[i * 4 + 3] = 255.toByte()
            }
        }

        // ── Pass 3: 中值滤波降噪 ──
        medianFilter(pixels, width, height, 3)

        return pixels
    }

    // ──── 内部方法 ────

    /** 3×3 box blur（近似高斯模糊） */
    private fun boxBlur(pixels: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius < 1) return pixels
        val result = pixels.copyOf()
        val n = w * h
        for (i in 0 until n) {
            val x = i % w
            val y = i / w
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            var count = 0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val ni = ny * w + nx
                        sumR += pixels[ni * 4].toInt() and 0xFF
                        sumG += pixels[ni * 4 + 1].toInt() and 0xFF
                        sumB += pixels[ni * 4 + 2].toInt() and 0xFF
                        sumA += pixels[ni * 4 + 3].toInt() and 0xFF
                        count++
                    }
                }
            }
            result[i * 4] = (sumR / count).toByte()
            result[i * 4 + 1] = (sumG / count).toByte()
            result[i * 4 + 2] = (sumB / count).toByte()
            result[i * 4 + 3] = (sumA / count).toByte()
        }
        return result
    }

    /** 3×3 中值滤波 */
    private fun medianFilter(pixels: ByteArray, w: Int, h: Int, ksize: Int) {
        if (ksize < 3 || ksize % 2 == 0) return
        val half = ksize / 2
        val original = pixels.copyOf()
        val n = w * h
        val neighbors = IntArray(ksize * ksize)
        for (i in 0 until n) {
            val x = i % w; val y = i / w
            var cnt = 0
            for (dy in -half..half) {
                for (dx in -half..half) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val ni = ny * w + nx
                        neighbors[cnt++] = (original[ni * 4].toInt() and 0xFF) shl 16 or
                                          ((original[ni * 4 + 1].toInt() and 0xFF) shl 8) or
                                          (original[ni * 4 + 2].toInt() and 0xFF)
                    }
                }
            }
            if (cnt == 0) continue
            neighbors.sort(0, cnt)
            val median = neighbors[cnt / 2]
            pixels[i * 4] = ((median shr 16) and 0xFF).toByte()
            pixels[i * 4 + 1] = ((median shr 8) and 0xFF).toByte()
            pixels[i * 4 + 2] = (median and 0xFF).toByte()
        }
    }
}