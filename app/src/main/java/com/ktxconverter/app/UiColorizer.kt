package com.ktxconverter.app

import android.content.Context
import org.json.JSONArray

/**
 * 调色板着色器 —— 与 Python ktx2png.py 完全一致的182阶暖红调色板映射
 *
 * 核心流程:
 * 1. ETC解码 → R=G=B=灰度, A=255
 * 2. 灰度 < 调色板最小亮度(18) → 全透明（去噪）
 * 3. 灰度 >= 18 → 二分查找最近调色板条目 → RGBA
 */
object UiColorizer {

    data class Palette(
        val lums: IntArray,
        val r: ByteArray,
        val g: ByteArray,
        val b: ByteArray,
        val a: ByteArray
    )

    /** 从 assets/颜色调色板.json 加载182阶调色板 */
    fun loadPalette(context: Context): Palette {
        val json = context.assets.open("颜色调色板.json").bufferedReader().readText()
        val arr = JSONArray(json)
        val n = arr.length()
        val lums = IntArray(n); val r = ByteArray(n)
        val g = ByteArray(n); val b = ByteArray(n); val a = ByteArray(n)
        for (i in 0 until n) {
            val obj = arr.getJSONObject(i)
            lums[i] = obj.getInt("lum")
            r[i] = obj.getInt("r").toByte()
            g[i] = obj.getInt("g").toByte()
            b[i] = obj.getInt("b").toByte()
            a[i] = obj.getInt("a").toByte()
        }
        return Palette(lums, r, g, b, a)
    }

    /**
     * 调色板着色 —— 与 Python apply_palette() 完全等价
     * @param grayPixels 解码后的 RGBA (R=G=B=灰度, A=255)
     * @param palette 调色板
     */
    fun colorizeWithPalette(
        grayPixels: ByteArray,
        palette: Palette,
        width: Int,
        height: Int
    ): ByteArray {
        val result = ByteArray(width * height * 4)
        val lums = palette.lums
        val minLum = lums[0]
        val n = lums.size

        for (i in 0 until width * height) {
            val gray = grayPixels[i * 4].toInt() and 0xFF

            if (gray < minLum) {
                result[i * 4] = 0; result[i * 4 + 1] = 0
                result[i * 4 + 2] = 0; result[i * 4 + 3] = 0
            } else {
                var idx = lums.binarySearch(gray)
                if (idx < 0) idx = -idx - 1
                if (idx >= n) idx = n - 1
                result[i * 4] = palette.r[idx]
                result[i * 4 + 1] = palette.g[idx]
                result[i * 4 + 2] = palette.b[idx]
                result[i * 4 + 3] = palette.a[idx]
            }
        }
        return result
    }

    /**
     * 调色板着色（灰度单通道直接输入）
     * @param gray 单通道灰度数组，每个像素一个字节
     * @param palette 调色板
     */
    fun colorizeGrayWithPalette(
        gray: ByteArray,
        palette: Palette,
        width: Int,
        height: Int
    ): ByteArray {
        val result = ByteArray(width * height * 4)
        val lums = palette.lums
        val minLum = lums[0]
        val n = lums.size

        for (i in gray.indices) {
            val g = gray[i].toInt() and 0xFF
            if (g < minLum) {
                result[i * 4] = 0; result[i * 4 + 1] = 0
                result[i * 4 + 2] = 0; result[i * 4 + 3] = 0
            } else {
                var idx = lums.binarySearch(g)
                if (idx < 0) idx = -idx - 1
                if (idx >= n) idx = n - 1
                result[i * 4] = palette.r[idx]
                result[i * 4 + 1] = palette.g[idx]
                result[i * 4 + 2] = palette.b[idx]
                result[i * 4 + 3] = palette.a[idx]
            }
        }
        return result
    }

    // ── 以下旧版 LUT 保留兼容 ──

    data class Luts(val r: IntArray, val g: IntArray, val b: IntArray, val a: IntArray)

    fun buildLutsFromTemplate(templatePixels: ByteArray, templateWidth: Int, templateHeight: Int): Luts {
        val pixelCount = templateWidth * templateHeight
        val bucketsR = Array<MutableList<Int>>(256) { mutableListOf() }
        val bucketsG = Array<MutableList<Int>>(256) { mutableListOf() }
        val bucketsB = Array<MutableList<Int>>(256) { mutableListOf() }
        val bucketsA = Array<MutableList<Int>>(256) { mutableListOf() }
        for (i in 0 until pixelCount) {
            val a = templatePixels[i * 4 + 3].toInt() and 0xFF
            if (a == 0) continue
            val r = templatePixels[i * 4].toInt() and 0xFF
            val g = templatePixels[i * 4 + 1].toInt() and 0xFF
            val b = templatePixels[i * 4 + 2].toInt() and 0xFF
            val lum = g
            bucketsR[lum].add(r); bucketsG[lum].add(g); bucketsB[lum].add(b); bucketsA[lum].add(a)
        }
        val lutR = IntArray(256); val lutG = IntArray(256); val lutB = IntArray(256); val lutA = IntArray(256)
        val hasData = BooleanArray(256); val hasAlpha = BooleanArray(256)
        for (i in 0..255) {
            if (bucketsR[i].isNotEmpty()) {
                lutR[i] = bucketsR[i].sorted()[bucketsR[i].size / 2]
                lutG[i] = bucketsG[i].sorted()[bucketsG[i].size / 2]
                lutB[i] = bucketsB[i].sorted()[bucketsB[i].size / 2]
                hasData[i] = true
            }
            if (bucketsA[i].isNotEmpty()) { lutA[i] = bucketsA[i].sorted()[bucketsA[i].size / 2]; hasAlpha[i] = true }
        }
        interpolate(lutR, hasData); interpolate(lutG, hasData)
        interpolate(lutB, hasData); interpolate(lutA, hasAlpha)
        return Luts(lutR, lutG, lutB, lutA)
    }

    fun colorize(grayPixels: ByteArray, luts: Luts, width: Int, height: Int): ByteArray {
        val result = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val lum = grayPixels[i * 4 + 1].toInt() and 0xFF
            if (lum == 0) {
                result[i * 4] = 0; result[i * 4 + 1] = 0; result[i * 4 + 2] = 0; result[i * 4 + 3] = 0
            } else {
                result[i * 4] = luts.r[lum].toByte(); result[i * 4 + 1] = luts.g[lum].toByte()
                result[i * 4 + 2] = luts.b[lum].toByte(); result[i * 4 + 3] = luts.a[lum].toByte()
            }
        }
        return result
    }

    fun buildLutsFromPalette(palette: Palette): Luts {
        val lutR = IntArray(256)
        val lutG = IntArray(256)
        val lutB = IntArray(256)
        val lutA = IntArray(256)
        val lums = palette.lums
        val n = lums.size
        var pi = 0
        for (i in 0..255) {
            while (pi < n - 1 && lums[pi + 1] <= i) pi++
            if (pi < n) {
                lutR[i] = palette.r[pi].toInt() and 0xFF
                lutG[i] = palette.g[pi].toInt() and 0xFF
                lutB[i] = palette.b[pi].toInt() and 0xFF
                lutA[i] = palette.a[pi].toInt() and 0xFF
            } else {
                lutR[i] = 255; lutG[i] = 255; lutB[i] = 255; lutA[i] = 255
            }
        }
        return Luts(lutR, lutG, lutB, lutA)
    }

    fun buildFineLut(): Luts {
        val lutR = intArrayOf(53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,53,54,56,57,59,61,62,64,66,67,68,70,71,73,74,76,77,79,80,82,83,85,87,88,90,91,93,94,96,97,99,100,102,104,105,107,108,110,112,113,114,115,117,118,120,121,123,124,125,126,128,130,132,133,134,135,137,138,139,141,142,144,146,147,148,149,151,153,154,155,156,157,159,160,162,163,164,165,167,169,170,171,172,173,174,176,177,179,180,181,182,183,184,186,187,188,190,191,192,193,194,196,197,198,199,201,202,203,204,205,206,207,208,209,211,212,213,214,215,216,217,218,219,220,221,223,223,225,225,226,226,227,228,228,229,229,230,230,231,231,232,232,233,233,234,234,235,235,236,236,237,237,238,238,238,239,239,240,240,241,241,242,242,242,242,243,244,244,244,245,245,245,246,246,246,247,247,247,248,248,248,249,249,249,249,250,250,250,250,251,251,251,251,252,252,252,252,253,253,253,253,253,253,253,254,254,254,254,254,254,254,254,254,255,255,255,255,255,255,255,255,255,255,255)
        val lutG = intArrayOf(22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,23,24,25,25,26,27,28,28,29,30,30,31,32,32,33,34,34,35,36,37,38,38,39,40,40,41,42,42,43,44,45,46,46,47,48,49,50,50,51,52,53,53,54,55,56,56,57,58,59,60,61,61,62,63,64,64,65,66,67,68,69,69,70,71,72,73,73,74,75,76,77,78,79,79,80,81,82,83,83,84,85,86,87,88,89,90,90,91,92,93,94,95,96,97,98,98,99,100,101,102,103,104,105,106,107,108,109,109,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,125,126,127,128,129,130,131,133,134,135,136,138,139,140,141,142,144,145,146,148,149,150,151,152,154,155,156,158,159,160,161,163,164,166,167,168,169,170,172,173,175,176,177,178,180,181,182,184,185,187,188,189,190,192,193,194,196,197,199,200,201,203,204,205,206,208,210,211,212,213,215,216,218,219,220,222,223,225,226,227,229,230,232,233,235,236,238,239,240,242,244,245,246,248,250,251,252,254,255)
        val lutB = intArrayOf(22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,22,23,24,25,25,26,27,28,28,29,30,30,31,32,32,33,34,34,35,36,37,38,38,39,40,40,41,42,42,43,44,45,46,46,47,48,49,50,50,51,52,53,53,54,55,56,56,57,58,59,60,61,61,62,63,64,64,65,66,67,68,69,69,70,71,72,73,73,74,75,76,77,78,79,79,80,81,82,83,83,84,85,86,87,88,89,90,90,91,92,93,94,95,96,97,98,98,99,100,101,102,103,104,105,106,107,108,109,109,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,125,126,127,128,129,130,131,133,134,135,136,138,139,140,141,142,144,145,146,148,149,150,151,152,154,155,156,158,159,160,161,163,164,166,167,168,169,170,172,173,175,176,177,178,180,181,182,184,185,187,188,189,190,192,193,194,196,197,199,200,201,203,204,205,206,208,210,211,212,213,215,216,218,219,220,222,223,225,226,227,229,230,232,233,235,236,238,239,240,242,244,245,246,248,250,251,252,254,255)
        val lutA = intArrayOf(15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,16,16,17,18,18,19,20,20,21,21,22,22,23,23,24,25,25,26,27,28,29,29,30,30,31,31,32,32,33,34,35,36,36,37,38,39,40,40,41,42,43,43,44,45,46,46,47,48,49,50,51,51,52,53,54,54,55,56,57,58,59,59,60,61,62,64,64,65,66,67,68,69,70,70,71,72,73,75,75,76,77,78,79,80,81,82,82,83,84,85,86,88,89,90,91,91,92,93,94,96,97,98,99,100,101,102,103,103,104,105,106,107,109,110,111,112,113,114,115,116,117,118,120,121,122,123,124,125,126,127,129,129,131,131,133,134,135,136,137,138,140,140,142,143,144,145,146,148,149,150,152,153,153,155,156,158,159,161,161,163,163,165,166,168,170,170,171,173,174,175,178,178,180,182,183,183,186,187,188,190,191,193,194,195,197,198,199,200,202,204,205,207,208,210,211,213,214,215,218,219,221,222,223,225,227,229,230,232,233,235,236,238,240,242,243,245,247,249,250,252,254,255)
        return Luts(lutR, lutG, lutB, lutA)
    }

    private fun interpolate(lut: IntArray, has: BooleanArray) {
        for (i in 0..255) {
            if (has[i]) continue
            var left = i - 1; var right = i + 1
            while (left >= 0 && !has[left]) left--
            while (right < 256 && !has[right]) right++
            lut[i] = when {
                left >= 0 && right < 256 -> { val t = (i - left).toFloat() / (right - left); (lut[left] + t * (lut[right] - lut[left])).toInt() }
                left >= 0 -> lut[left]
                right < 256 -> lut[right]
                else -> 0
            }
        }
    }
}