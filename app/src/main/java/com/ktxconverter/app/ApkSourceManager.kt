package com.ktxconverter.app

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream

/**
 * APK 源码管理器：从 APK (ZIP) 中扫描和提取 KTX/Lua 文件
 */
object ApkSourceManager {

    /** APK 内固定路径 */
    private const val KTX_DIR = "assets/Data/Images/Bin/ETC2/"
    private const val LUA_DIR = "assets/Data/Resources/"

    data class KtxEntry(
        val name: String,          // 例如 "UIPackedAtlas27.ktx"
        val path: String,          // 在APK内的完整路径
        val size: Long,
        val isAtlas: Boolean,      // 是否UIPackedAtlas系列
        val isR11: Boolean = false // 是否R11_EAC格式（单色）
    )

    data class ScanResult(
        val allKtx: List<KtxEntry>,
        val atlasKtx: List<KtxEntry>,
        val otherKtx: List<KtxEntry>,
        val r11Ktx: List<KtxEntry> = emptyList(),     // R11_EAC 单色
        val colorKtx: List<KtxEntry> = emptyList(),    // ETC2_RGBA 彩色
        val luaEntry: String? = null
    )

    private fun isR11Format(data: ByteArray): Boolean {
        // 读取 KTX 头部判断 internal format
        return try {
            if (data.size < 40) return false
            // 跳过 12 字节 magic + 4 字节 endian
            var off = 16
            // glType (4) + glTypeSize (4) + glFormat (4)
            off += 12
            // glInternalFormat
            val endian = if (data[12].toInt() == 0x01) '>' else '<'
            val gi = if (endian == '<') {
                (data[off].toInt() and 0xFF) or
                ((data[off+1].toInt() and 0xFF) shl 8) or
                ((data[off+2].toInt() and 0xFF) shl 16) or
                ((data[off+3].toInt() and 0xFF) shl 24)
            } else {
                ((data[off].toInt() and 0xFF) shl 24) or
                ((data[off+1].toInt() and 0xFF) shl 16) or
                ((data[off+2].toInt() and 0xFF) shl 8) or
                (data[off+3].toInt() and 0xFF)
            }
            gi == 0x9270 || gi == 0x9271  // GL_R11 or GL_SR11
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 扫描 APK (作为ZIP) 中的 KTX 和 Lua 资源
     */
    fun scanApk(context: Context, apkUri: Uri): ScanResult {
        val allKtx = mutableListOf<KtxEntry>()
        var luaEntry: String? = null

        context.contentResolver.openInputStream(apkUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryPath = entry.name
                    when {
                        entryPath.startsWith(KTX_DIR) && entryPath.endsWith(".ktx") -> {
                            val name = entryPath.substringAfterLast('/')
                            val isAtlas = name.startsWith("UIPackedAtlas")
                            // 收集所有 KTX
                            allKtx.add(KtxEntry(
                                name = name,
                                path = entryPath,
                                size = entry.size,
                                isAtlas = isAtlas
                            ))
                        }
                        entryPath.startsWith(LUA_DIR) && entryPath.endsWith(".lua") -> {
                            // 记录所有 Lua，优先 UIPackedAtlas.lua
                            if (luaEntry == null || entryPath.contains("UIPackedAtlas")) {
                                luaEntry = entryPath
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val atlasKtx = allKtx.filter { it.isAtlas }.sortedBy {
            // 按数字排序
            it.name.replace("UIPackedAtlas", "").replace(".ktx", "").toIntOrNull() ?: 999
        }
        val otherKtx = allKtx.filter { !it.isAtlas }.sortedBy { it.name }

        return ScanResult(
            allKtx = allKtx,
            atlasKtx = atlasKtx,
            otherKtx = otherKtx,
            r11Ktx = emptyList(),  // 在转换时动态分类
            colorKtx = emptyList(),
            luaEntry = luaEntry
        )
    }

    /**
     * 从 APK 中读取指定条目的内容
     */
    fun readEntry(context: Context, apkUri: Uri, entryPath: String): ByteArray? {
        context.contentResolver.openInputStream(apkUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == entryPath) {
                        return zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }

    /**
     * 批量读取：一次打开 APK，按顺序读取列表中的条目
     * 相比逐个 readEntry 调用，避免了重复打开/遍历 ZIP
     * @return Map<entryPath, data> 包含成功读取的条目
     */
    fun readEntries(context: Context, apkUri: Uri, paths: List<String>): Map<String, ByteArray> {
        val targets = paths.toSet()
        val result = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(apkUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name in targets) {
                        result[entry.name] = zis.readBytes()
                        if (result.size == targets.size) break // 全部找到
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return result
    }
}
