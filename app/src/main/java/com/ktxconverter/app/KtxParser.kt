package com.ktxconverter.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

object KtxParser {
    val KTX1_IDENTIFIER = byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    const val GL_ETC1_RGB8 = 0x8D64
    const val GL_COMPRESSED_RGB8_ETC2 = 0x9274
    const val GL_COMPRESSED_SRGB8_ETC2 = 0x9275
    const val GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2 = 0x9276
    const val GL_COMPRESSED_SRGB8_PUNCHTHROUGH_ALPHA1_ETC2 = 0x9277
    const val GL_COMPRESSED_RGBA8_ETC2_EAC = 0x9278
    const val GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC = 0x9279
    const val GL_COMPRESSED_R11_EAC = 0x9270
    const val GL_COMPRESSED_RG11_EAC = 0x9272
    const val GL_COMPRESSED_SIGNED_R11_EAC = 0x9271
    const val GL_COMPRESSED_SIGNED_RG11_EAC = 0x9273
    const val GL_RGB = 0x1907
    const val GL_RGBA = 0x1908
    const val GL_RED = 0x1903
    const val GL_RG = 0x8227
    const val GL_UNSIGNED_BYTE = 0x1401
    const val GL_UNSIGNED_SHORT = 0x1403
    const val GL_RGBA8 = 0x8058
    const val GL_RGB8 = 0x8051
    const val GL_R8 = 0x8229

    data class KtxHeader(var endianness: Int = 0, var glType: Int = 0, var glTypeSize: Int = 0, var glFormat: Int = 0, var glInternalFormat: Int = 0, var glBaseInternalFormat: Int = 0, var pixelWidth: Int = 0, var pixelHeight: Int = 0, var pixelDepth: Int = 0, var numArrayElements: Int = 0, var numFaces: Int = 0, var numMipmapLevels: Int = 0, var bytesOfKeyValueData: Int = 0, val kvPairs: MutableMap<String,String> = mutableMapOf())
    data class ParsedKtx(val header: KtxHeader, val pixelData: ByteArray)

    fun parse(data: ByteArray): ParsedKtx {
        if (data.size < 64) throw IllegalArgumentException("文件太小")
        if (!data.sliceArray(0 until 12).contentEquals(KTX1_IDENTIFIER)) throw IllegalArgumentException("不是有效的 KTX1 文件")
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        val h = KtxHeader()
        h.endianness = buf.int
        val endian = if (h.endianness == 0x04030201) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        buf.order(endian)
        h.glType = buf.int; h.glTypeSize = buf.int; h.glFormat = buf.int; h.glInternalFormat = buf.int
        h.glBaseInternalFormat = buf.int; h.pixelWidth = buf.int; h.pixelHeight = buf.int
        h.pixelDepth = buf.int; h.numArrayElements = buf.int; h.numFaces = buf.int
        h.numMipmapLevels = buf.int; h.bytesOfKeyValueData = buf.int
        val kvEnd = buf.position() + h.bytesOfKeyValueData
        while (buf.position() < kvEnd) {
            val kvLen = buf.int
            if (kvLen == 0) continue
            val kvBytes = ByteArray(kvLen); buf.get(kvBytes)
            buf.position((buf.position() + 3) and 3.inv())
            val nullPos = kvBytes.indexOf(0)
            if (nullPos > 0) {
                val key = String(kvBytes, 0, nullPos, Charsets.US_ASCII)
                val valueEnd = if (kvBytes.lastOrNull() == 0.toByte()) kvBytes.size - 1 else kvBytes.size
                val value = String(kvBytes, nullPos + 1, valueEnd - nullPos - 1, Charsets.US_ASCII)
                h.kvPairs[key] = value
            }
        }
        val numMips = maxOf(1, h.numMipmapLevels)
        var mip0Data = ByteArray(0)
        for (mipIdx in 0 until numMips) {
            val imageSize = buf.int
            val d = ByteArray(imageSize); buf.get(d)
            if (mipIdx == 0) mip0Data = d
        }
        return ParsedKtx(h, mip0Data)
    }

    fun formatName(fmt: Int) = when(fmt) {
        GL_ETC1_RGB8 -> "ETC1_RGB8"; GL_COMPRESSED_RGB8_ETC2 -> "ETC2_RGB8"
        GL_COMPRESSED_SRGB8_ETC2 -> "SRGB8_ETC2"; GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2 -> "ETC2_RGB8_A1"
        GL_COMPRESSED_SRGB8_PUNCHTHROUGH_ALPHA1_ETC2 -> "SRGB8_ETC2_A1"; GL_COMPRESSED_RGBA8_ETC2_EAC -> "ETC2_RGBA8_EAC"
        GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC -> "SRGB8_ALPHA8_ETC2_EAC"; GL_COMPRESSED_R11_EAC -> "R11_EAC"
        GL_COMPRESSED_RG11_EAC -> "RG11_EAC"; GL_COMPRESSED_SIGNED_R11_EAC -> "SIGNED_R11_EAC"
        GL_COMPRESSED_SIGNED_RG11_EAC -> "SIGNED_RG11_EAC"; GL_RGBA8 -> "RGBA8"; GL_RGB8 -> "RGB8"; GL_R8 -> "R8"
        else -> "UNKNOWN(0x${fmt.toString(16).uppercase().padStart(4,'0')})"
    }
}