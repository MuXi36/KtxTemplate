package com.ktxconverter.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ETC2 + EAC 编码器 (PNG → KTX)
 * 移植自 Python 的简单编码逻辑
 */
object EtcEncoder {

    fun clampByte(v: Int) = v.coerceIn(0, 255)

    /**
     * 编码单个 4x4 块为 ETC2_RGB8 (8 bytes)
     * blockBgra: 64 bytes (16 pixels x BGRA)
     * 简化编码：选取平均色 + 固定 modifier
     */
    fun encodeEtc2Block(blockBgra: ByteArray, offset: Int = 0): ByteArray {
        var sr = 0; var sg = 0; var sb = 0
        for (i in 0 until 16) {
            val idx = offset + i * 4
            sr += blockBgra[idx + 2].toInt() and 0xFF  // R
            sg += blockBgra[idx + 1].toInt() and 0xFF  // G
            sb += blockBgra[idx].toInt() and 0xFF      // B
        }

        val r1 = kotlin.math.round(sr / 272.0).toInt() and 0xF
        val g1 = kotlin.math.round(sg / 272.0).toInt() and 0xF
        val b1 = kotlin.math.round(sb / 272.0).toInt() and 0xF

        // 构建高32位: diffbit=0, flipbit=1, cw1=cw2=5
        val hi = (1 shl 30) or (5 shl 27) or (5 shl 24) or
                 (b1 shl 20) or (g1 shl 16) or (r1 shl 12) or
                 (b1 shl 8) or (g1 shl 4) or r1

        val br = (r1 shl 4) or r1
        var lo = 0
        for (i in 0 until 16) {
            val rVal = blockBgra[offset + i * 4 + 2].toInt() and 0xFF
            val d = rVal - br
            val idx = when {
                d < -30 -> 1
                d > 30 -> 2
                else -> 0
            }
            lo = lo or ((idx and 1) shl i)
            lo = lo or (((idx shr 1) and 1) shl (i + 16))
        }

        val result = ByteArray(8)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(hi)
        buf.putInt(lo)
        return result
    }

    /**
     * 编码单个 4x4 块的 alpha 通道为 EAC (8 bytes)
     */
    fun encodeEacBlock(alphaValues: ByteArray, offset: Int = 0): ByteArray {
        var sa = 0
        for (i in 0 until 16) sa += alphaValues[offset + i].toInt() and 0xFF
        val base = clampByte(kotlin.math.round(sa / 16.0).toInt())
        val hi = base or (5 shl 8) or (1 shl 13)
        var lo = 0L
        for (i in 0 until 16) {
            lo = lo or (3L shl (i * 3))
        }
        lo = lo and 0xFFFFFFFFL

        val result = ByteArray(8)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(hi)
        buf.putInt(lo.toInt())
        return result
    }

    /** 补齐到4的倍数 */
    fun pad4(x: Int) = (x + 3) and 3.inv()

    /** 提取 4x4 块 (BGRA 格式) */
    fun extractBlock(bgraData: ByteArray, imgWidth: Int, bx: Int, by: Int): ByteArray {
        val block = ByteArray(64)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val src = ((by * 4 + y) * imgWidth + bx * 4 + x) * 4
                val dst = (y * 4 + x) * 4
                System.arraycopy(bgraData, src, block, dst, 4)
            }
        }
        return block
    }

    /** 压缩 BGRA 图像为 ETC2_RGB8 */
    fun compressEtc2Rgb(bgraData: ByteArray, w: Int, h: Int): ByteArray {
        val bx = w / 4
        val by = h / 4
        val output = ByteArray(bx * by * 8)
        for (y in 0 until by) {
            for (x in 0 until bx) {
                val blk = extractBlock(bgraData, w, x, y)
                val compressed = encodeEtc2Block(blk)
                System.arraycopy(compressed, 0, output, (y * bx + x) * 8, 8)
            }
        }
        return output
    }

    /** 压缩 BGRA 图像为 ETC2_RGBA8_EAC */
    fun compressEtc2Rgba(bgraData: ByteArray, w: Int, h: Int): ByteArray {
        val bx = w / 4
        val by = h / 4
        val output = ByteArray(bx * by * 16)
        for (y in 0 until by) {
            for (x in 0 until bx) {
                val blk = extractBlock(bgraData, w, x, y)
                val off = (y * bx + x) * 16
                val alphaVals = ByteArray(16) { i -> blk[i * 4 + 3] }
                val eacEncoded = encodeEacBlock(alphaVals)
                val etcEncoded = encodeEtc2Block(blk)
                System.arraycopy(eacEncoded, 0, output, off, 8)
                System.arraycopy(etcEncoded, 0, output, off + 8, 8)
            }
        }
        return output
    }

    /** RGBA → BGRA 转换 + pad */
    fun rgbaToBgra(rgbaPixels: ByteArray, w: Int, h: Int,
                    pw: Int, ph: Int, hasAlpha: Boolean): ByteArray {
        val bgra = ByteArray(pw * ph * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = (y * w + x) * 4
                val dst = (y * pw + x) * 4
                bgra[dst + 0] = rgbaPixels[src + 2]  // B
                bgra[dst + 1] = rgbaPixels[src + 1]  // G
                bgra[dst + 2] = rgbaPixels[src + 0]  // R
                bgra[dst + 3] = if (hasAlpha) rgbaPixels[src + 3] else 127.toByte()
            }
        }
        return bgra
    }

    /** 检测是否有非255的 alpha */
    fun detectAlpha(rgbaPixels: ByteArray): Boolean {
        for (i in 3 until rgbaPixels.size step 4) {
            if ((rgbaPixels[i].toInt() and 0xFF) != 255) return true
        }
        return false
    }

    /**
     * 构建 KTX1 文件
     */
    fun buildKtx1(width: Int, height: Int, compressedData: ByteArray, hasAlpha: Boolean): ByteArray {
        val kvSize = 0
        val dataSize = compressedData.size
        val totalSize = 12 + 4 + 13 * 4 + 4 + dataSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // KTX 标识符
        buf.put(KtxParser.KTX1_IDENTIFIER)
        // endianness
        buf.putInt(0x04030201)
        // 13 个 U32 字段
        buf.putInt(0)  // glType
        buf.putInt(1)  // glTypeSize
        buf.putInt(0)  // glFormat
        buf.putInt(if (hasAlpha) KtxParser.GL_COMPRESSED_RGBA8_ETC2_EAC
                   else KtxParser.GL_COMPRESSED_RGB8_ETC2)
        buf.putInt(if (hasAlpha) KtxParser.GL_RGBA else KtxParser.GL_RGB)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(0)  // depth
        buf.putInt(0)  // numArrayElements
        buf.putInt(1)  // numFaces (py says 1 for cubemap not used)
        buf.putInt(1)  // numMipmapLevels
        buf.putInt(kvSize)

        // mip0 size + data
        buf.putInt(dataSize)
        buf.put(compressedData)

        return buf.array()
    }
}