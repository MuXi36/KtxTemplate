package com.ktxconverter.app

/**
 * UIPackedAtlas 图集切割工具
 *
 * 从 KTX 图集 + Lua坐标元数据 → 切割出单个UI元素PNG，按前缀自动分类
 * 移植自 atlas_cutter.py
 *
 * 分类规则基于光遇中文UI术语
 */
object AtlasCutter {

    data class ImageRegion(
        val name: String,
        val atlas: String,
        val u1: Float,
        val v1: Float,
        val u2: Float,
        val v2: Float
    )

    data class CropResult(
        val name: String,
        val category: String,
        val pixels: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * 解析 Lua 中的 ImageRegion 定义
     * 格式: resource "ImageRegion" "Name" { image = "AtlasName", uv = { u1, v1, u2, v2 } }
     */
    private val regionPattern = Regex(
        """resource\s+"ImageRegion"\s+"([^"]+)"\s*\{\s*image\s*=\s*"([^"]+)",\s*uv\s*=\s*\{\s*([^}]+)\s*\}\s*\}"""
    )

    fun parseAtlasLua(content: String): List<ImageRegion> {
        return regionPattern.findAll(content).mapNotNull { match ->
            try {
                val name = match.groupValues[1]
                val atlas = match.groupValues[2]
                val uvStr = match.groupValues[3]
                val parts = uvStr.split(",").map { it.trim() }
                if (parts.size >= 4) {
                    val u1 = evalFloat(parts[0])
                    val v1 = evalFloat(parts[1])
                    val u2 = evalFloat(parts[2])
                    val v2 = evalFloat(parts[3])
                    ImageRegion(name, atlas, u1, v1, u2, v2)
                } else null
            } catch (e: Exception) {
                null
            }
        }.toList()
    }

    /**
     * 从图集像素中裁剪指定 UV 区域（带边缘精修）
     */
    fun cropRegion(
        pixels: ByteArray, atlasW: Int, atlasH: Int,
        u1: Float, v1: Float, u2: Float, v2: Float,
        skipSharpen: Boolean = false
    ): CropData? {
        // 用 floor/ceil 向外扩展2px 捕获抗锯齿边缘（ETC2块压缩需要更大边距）
        val pad = 2
        val x1 = kotlin.math.floor(u1 * atlasW).toInt().coerceIn(0, atlasW - 1) - pad
        val y1 = kotlin.math.floor(v1 * atlasH).toInt().coerceIn(0, atlasH - 1) - pad
        val x2 = kotlin.math.ceil(u2 * atlasW).toInt().coerceIn(1, atlasW) + pad
        val y2 = kotlin.math.ceil(v2 * atlasH).toInt().coerceIn(1, atlasH) + pad

        val cx1 = x1.coerceAtLeast(0)
        val cy1 = y1.coerceAtLeast(0)
        val cx2 = x2.coerceAtMost(atlasW)
        val cy2 = y2.coerceAtMost(atlasH)

        val w = cx2 - cx1
        val h = cy2 - cy1
        if (w <= 0 || h <= 0) return null

        val cropped = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val srcRow = (cy1 + y) * atlasW * 4 + cx1 * 4
            val dstRow = y * w * 4
            System.arraycopy(pixels, srcRow, cropped, dstRow, w * 4)
        }

        // 去块 + 锐化（保持边缘清晰）；ENHANCE 模式下跳过（已有 blur+二值化）
        val processed = if (skipSharpen) cropped else sharpenEdges(cropped, w, h)
        // 裁剪透明边距
        return trimTransparent(processed, w, h)
    }

    /**
     * 边缘锐化：反锐化掩模，保持边缘清晰
     * 对 ETC2 块压缩导致的模糊做小幅锐化
     */
    private fun sharpenEdges(pixels: ByteArray, w: Int, h: Int): ByteArray {
        val result = pixels.copyOf()
        val amount = 0.25f  // 轻量锐化，避免边缘光晕
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = (y * w + x) * 4
                val a = result[idx + 3].toInt() and 0xFF
                // 只处理完全不透明的内部像素，保护边缘渐变
                if (a <= 200) continue
                // 对 RGB 三通道做反锐化掩模
                for (c in 0..2) {
                    val center = result[idx + c].toInt() and 0xFF
                    // 4邻域均值
                    val avg = (
                        (result[((y-1)*w + x) * 4 + c].toInt() and 0xFF) +
                        (result[((y+1)*w + x) * 4 + c].toInt() and 0xFF) +
                        (result[(y*w + x-1) * 4 + c].toInt() and 0xFF) +
                        (result[(y*w + x+1) * 4 + c].toInt() and 0xFF)
                    ) / 4
                    // unsharp: result = center + (center - avg) * amount
                    val sharp = center + ((center - avg) * amount).toInt()
                    result[idx + c] = sharp.coerceIn(0, 255).toByte()
                }
            }
        }
        return result
    }

    /**
     * 裁剪四周全透明像素（保留1px边距防止再次出现硬边）
     */
    private fun trimTransparent(pixels: ByteArray, w: Int, h: Int): CropData {
        var left = 0
        var top = 0
        var right = w
        var bottom = h

        // 从上往下找第一个不透明行
        for (y in 0 until h) {
            if (!rowAllTransparent(pixels, w, y)) { top = y; break }
        }
        // 从下往上
        for (y in h - 1 downTo 0) {
            if (!rowAllTransparent(pixels, w, y)) { bottom = y + 1; break }
        }
        // 从左往右
        for (x in 0 until w) {
            if (!colAllTransparent(pixels, w, h, x)) { left = x; break }
        }
        // 从右往左
        for (x in w - 1 downTo 0) {
            if (!colAllTransparent(pixels, w, h, x)) { right = x + 1; break }
        }

        // 保留2px边距（与裁切边距一致）
        left = (left - 2).coerceAtLeast(0)
        top = (top - 2).coerceAtLeast(0)
        right = (right + 2).coerceAtMost(w)
        bottom = (bottom + 2).coerceAtMost(h)

        val nw = right - left
        val nh = bottom - top
        if (nw <= 0 || nh <= 0) return CropData(pixels, w, h)

        val trimmed = ByteArray(nw * nh * 4)
        for (y in 0 until nh) {
            val srcRow = (top + y) * w * 4 + left * 4
            System.arraycopy(pixels, srcRow, trimmed, y * nw * 4, nw * 4)
        }
        return CropData(trimmed, nw, nh)
    }

    private fun rowAllTransparent(pixels: ByteArray, w: Int, y: Int): Boolean {
        val rowStart = y * w * 4
        for (x in 0 until w) {
            if ((pixels[rowStart + x * 4 + 3].toInt() and 0xFF) > 0) return false
        }
        return true
    }

    private fun colAllTransparent(pixels: ByteArray, w: Int, h: Int, x: Int): Boolean {
        for (y in 0 until h) {
            if ((pixels[(y * w + x) * 4 + 3].toInt() and 0xFF) > 0) return false
        }
        return true
    }

    /**
     * 按名称前缀分类（光遇中文UI术语）
     * 分类名中的 / 替换为 _ 避免产生嵌套子目录
     */
    fun classify(name: String): String {
        val rules = listOf(
            "UiEmote" to "表情",
            "UiButton" to "按键图标",
            "UiMisc" to "杂项图标",
            "UiLightBulb" to "杂项图标",
            "UiMap" to "地图",
            "UiAnalog" to "摇杆",
            "UiBorder" to "边框",
            "UiBrand" to "品牌",
            "UiCrab" to "螃蟹",
            "UiDisplay" to "显示",
            "UiFreshman" to "新手引导",
            "UiMenu" to "菜单",
            "UiHud" to "界面",
            "UiOutfitBody" to "装扮_裤子",
            "UiOutfitCape" to "装扮_斗篷",
            "UiOutfitHair" to "装扮_发型",
            "UiOutfitHorn" to "装扮_头角",
            "UiOutfitMask" to "装扮_面具",
            "UiOutfitProp" to "装扮_道具",
            "UiOutfitFeet" to "装扮_鞋子",
            "UiOutfitFace" to "装扮_面部",
            "UiOutfitNeck" to "装扮_颈饰",
            "UiOutfitHat" to "装扮_头饰",
            "UiOutfitTail" to "装扮_尾巴",
            "UiOutfitWing" to "装扮_翅膀",
            "UiOutfitNone" to "装扮_占位",
            "UiOutfit" to "装扮_其他",
            "UiPersonality" to "性格图标",
            "UiSharedSpace" to "共享空间",
            "UiSocial" to "社交",
            "UiSeason" to "季节",
            "UiMatch" to "匹配",
            "UiToggle" to "开关",
            "UiRadial" to "径向渐变",
            "UiGradient" to "渐变",
            "UiMusic" to "音乐",
            "UiPlaceholder" to "占位图",
            "UiWip" to "开发中",
        )
        for ((prefix, folder) in rules) {
            if (name.startsWith(prefix)) return folder
        }
        return "其他"
    }

    data class CropData(
        val pixels: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * 解析浮点数（支持分数形式 1/2048 和普通小数 0.0625）
     */
    private fun evalFloat(expr: String): Float {
        return try {
            if (expr.contains("/")) {
                val parts = expr.split("/")
                parts[0].trim().toFloat() / parts[1].trim().toFloat()
            } else {
                expr.toFloat()
            }
        } catch (e: Exception) {
            0f
        }
    }
}