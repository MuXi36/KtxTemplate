package com.ktxconverter.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PvrKtxConverter {
    private const val TAG = "PvrKtx"
    private const val DEPLOY_VERSION = 10  // v10: --gray 灰度输出 + 模板LUT回退

    /** Android 10+ W^X：filesDir noexec，但 nativeLibraryDir 天然可执行。
     *  proroot（零开销 proot）放在 jniLibs 中，负责在 glibc 环境内运行 rootfs 中的 ELF。 */
    private fun nativeDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    /** proroot 启动器 —— 来自 nativeLibraryDir */
    private fun prorootPath(ctx: Context) = "${nativeDir(ctx)}/libproroot.so"

    /** 提取后的 Linux rootfs 根目录 */
    private fun linuxRoot(ctx: Context) = File(ctx.filesDir, "linux")

    /** rootfs 是否已部署（检查版本标记 + 关键文件） */
    fun isReady(ctx: Context): Boolean {
        val versionFile = File(linuxRoot(ctx), ".deploy_version")
        if (!versionFile.exists()) return false
        val ver = try { versionFile.readText().trim().toIntOrNull() } catch (_: Exception) { null }
        return ver == DEPLOY_VERSION &&
            File(prorootPath(ctx)).canExecute() &&
            File(linuxRoot(ctx), "bin/python3").exists() &&
            File(linuxRoot(ctx), "bin/ktx2png.py").exists()
    }

    /** 首次部署：从 assets 完整复制 rootfs + 创建符号链接 */
    suspend fun deploy(ctx: Context, onProgress: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isReady(ctx)) return@withContext true
        try {
            val root = linuxRoot(ctx)
            // 清空旧 rootfs，避免 copyAssetsDir 的 exists 跳过更新
            root.deleteRecursively()
            root.mkdirs()
            onProgress("[PVR] 正在部署 Linux 环境...")
            copyAssetsDir(ctx, "linux", root)

            // ktx2png.py 期望 PVR_CLI 在 /home/pvr/CLI/Linux_armv8_64/PVRTexToolCLI
            // 在 rootfs 内 python3 & pvr_cli 都在 bin/ 下，创建符号链接
            // 注意：链接目标必须用 rootfs 内路径（不是宿主机绝对路径），proroot 才能解析
            val pvrHome = File(root, "home/pvr/CLI/Linux_armv8_64")
            pvrHome.mkdirs()
            val pvrCliLink = File(pvrHome, "PVRTexToolCLI")
            pvrCliLink.delete()
            try {
                ProcessBuilder("ln", "-sf", "/bin/pvr_cli", pvrCliLink.absolutePath)
                    .start().waitFor()
            } catch (_: Exception) {
                ProcessBuilder("cp", "${root}/bin/pvr_cli", pvrCliLink.absolutePath)
                    .start().waitFor()
            }
            // PVR_LIB → /lib （rootfs 中的 glibc 库）
            val pvrLibHome = File(root, "home/pvr/Library/Linux_armv8_64")
            pvrLibHome.mkdirs()
            val pvrLibLink = File(pvrLibHome, "lib")
            pvrLibLink.delete()
            try {
                ProcessBuilder("ln", "-sf", "/lib", pvrLibLink.absolutePath)
                    .start().waitFor()
            } catch (_: Exception) {}

            // 先写版本标记，再检查 isReady
            File(root, ".deploy_version").writeText(DEPLOY_VERSION.toString())
            // 确保 pvr_cli 可执行
            ProcessBuilder("chmod", "755", "${root}/bin/pvr_cli").start().waitFor()
            // 确保 astcenc 可执行
            ProcessBuilder("chmod", "755", "${root}/bin/astcenc").start().waitFor()
            val ok = isReady(ctx)
            if (ok) onProgress("[PVR] 部署完成") else onProgress("[PVR] 部署失败")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "deploy failed", e)
            onProgress("[PVR] 部署异常: ${e.message}")
            false
        }
    }

    /** 递归复制 assets 目录（完整复制，不再跳过 ELF——它们在 rootfs 内由 proroot linker 加载） */
    private fun copyAssetsDir(ctx: Context, assetPath: String, destDir: File) {
        val assets = ctx.assets
        destDir.mkdirs()
        for (name in assets.list(assetPath) ?: emptyArray()) {
            val srcPath = "$assetPath/$name"
            val dst = File(destDir, name)
            val subList = try { assets.list(srcPath) } catch (_: Exception) { null }
            if (subList != null && subList.isNotEmpty()) {
                copyAssetsDir(ctx, srcPath, dst)
            } else {
                // 始终覆盖（不检查 exists，确保更新生效）
                assets.open(srcPath).use { src ->
                    dst.outputStream().use { out -> src.copyTo(out) }
                }
            }
        }
    }

    suspend fun convertToRgba(
        ctx: Context, ktxBytes: ByteArray,
        scriptName: String = "ktx2png.py",
        scriptArgs: List<String> = emptyList(),
        onProgress: (String) -> Unit = {}
    ): ByteArray? = withContext(Dispatchers.IO) {
        val ktxFile = File(ctx.cacheDir, "pvr_in.ktx")
        val pngFile = File(ctx.cacheDir, "pvr_out.png")
        try {
            ktxFile.writeBytes(ktxBytes)
            pngFile.delete()
            val root = linuxRoot(ctx)

            // proroot 把 rootfs 当 /，-b 把宿主 cacheDir 绑定到 /mnt
            // 直接调用 python3（不用 sh -c，rootfs 里没有 shell）
            val cmd = mutableListOf(
                prorootPath(ctx),
                "-r", root.absolutePath,
                "-b", "${ctx.cacheDir}:/mnt",
                "-0", "--link2symlink",
                "-w", "/",
                "/bin/python3", "/bin/$scriptName"
            )
            cmd.addAll(scriptArgs)
            cmd.addAll(listOf("/mnt/pvr_in.ktx", "/mnt/pvr_out.png"))
            
            val pb = ProcessBuilder(cmd)
                .directory(ctx.filesDir)
                .redirectErrorStream(true)

            val procEnv = pb.environment()
            procEnv["PROROOT_TMP_DIR"] = ctx.filesDir.absolutePath
            procEnv["PYTHONHOME"] = "/"
            procEnv["PYTHONPATH"] = "/python/lib/python3.12"
            procEnv["HOME"] = "/home"

            onProgress("[PVR] 启动解码...")
            val proc = pb.start()

            // 逐行读取输出
            val output = StringBuilder()
            proc.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    onProgress(line.trim())
                    line = reader.readLine()
                }
            }
            val exitCode = proc.waitFor()
            Log.d(TAG, output.toString().trim())

            val ok = exitCode == 0 && pngFile.isFile && pngFile.length() > 0
            if (!ok) {
                onProgress("[PVR] 失败 exit=$exitCode")
                Log.w(TAG, "exit=$exitCode pngSize=${pngFile.length()}")
            }
            if (ok) pngToRgba(pngFile) else null
        } catch (e: Exception) {
            onProgress("[PVR] 异常: ${e.message}")
            Log.e(TAG, "convert", e)
            null
        } finally {
            ktxFile.delete()
            pngFile.delete()
        }
    }

    private fun pngToRgba(file: File): ByteArray {
        val bytes = file.readBytes()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw Exception("Bad PNG")
        val w = bmp.width; val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h); bmp.recycle()
        val rgba = ByteArray(w * h * 4)
        for (i in pix.indices) {
            val p = pix[i]
            rgba[i * 4]     = ((p shr 16) and 0xFF).toByte()
            rgba[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
            rgba[i * 4 + 2] = (p and 0xFF).toByte()
            rgba[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
        }
        return rgba
    }

    /** 检查 ktxBytes 是否为 ASTC 格式 */
    fun isAstcFormat(ktxBytes: ByteArray): Boolean {
        if (ktxBytes.size < 64) return false
        val glFmt = (ktxBytes[28].toInt() and 0xFF) or
                    ((ktxBytes[29].toInt() and 0xFF) shl 8) or
                    ((ktxBytes[30].toInt() and 0xFF) shl 16) or
                    ((ktxBytes[31].toInt() and 0xFF) shl 24)
        // ASTC range: 0x93B0-0x93BD, 0x93D0-0x93DD
        // 0x9270-0x927D = R11_EAC → PVR+palette pipeline
        return (glFmt in 0x93B0..0x93BD) || (glFmt in 0x93D0..0x93DD)
    }

    /** ASTC 原生解码 via astcenc - ktx2png_astc.py */
    suspend fun convertAstcToRgba(
        ctx: Context, ktxBytes: ByteArray,
        onProgress: (String) -> Unit = {}
    ): ByteArray? = withContext(Dispatchers.IO) {
        val ktxFile = File(ctx.cacheDir, "astc_in.ktx")
        val pngFile = File(ctx.cacheDir, "astc_out.png")
        try {
            ktxFile.writeBytes(ktxBytes)
            pngFile.delete()
            val root = linuxRoot(ctx)

            val pb = ProcessBuilder(
                prorootPath(ctx),
                "-r", root.absolutePath,
                "-b", "${ctx.cacheDir}:/mnt",
                "-0", "--link2symlink",
                "-w", "/",
                "/bin/python3", "/bin/ktx2png_astc.py",
                "/mnt/astc_in.ktx", "/mnt/astc_out.png"
            )
                .directory(ctx.filesDir)
                .redirectErrorStream(true)

            val procEnv = pb.environment()
            procEnv["PROROOT_TMP_DIR"] = ctx.filesDir.absolutePath
            procEnv["PYTHONHOME"] = "/"
            procEnv["PYTHONPATH"] = "/python/lib/python3.12"
            procEnv["HOME"] = "/home"

            onProgress("[ASTC] 启动 astcenc 解码...")
            val proc = pb.start()

            val output = StringBuilder()
            proc.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    onProgress(line.trim())
                    line = reader.readLine()
                }
            }
            val exitCode = proc.waitFor()
            Log.d(TAG, output.toString().trim())

            val ok = exitCode == 0 && pngFile.isFile && pngFile.length() > 0
            if (!ok) {
                onProgress("[ASTC] 失败 exit=$exitCode")
                Log.w(TAG, "astc exit=$exitCode pngSize=${pngFile.length()}")
            }
            if (ok) pngToRgba(pngFile) else null
        } catch (e: Exception) {
            onProgress("[ASTC] 异常: ${e.message}")
            Log.e(TAG, "astc convert", e)
            null
        } finally {
            ktxFile.delete()
            pngFile.delete()
        }
    }
}