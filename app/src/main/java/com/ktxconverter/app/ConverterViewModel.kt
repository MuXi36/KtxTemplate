package com.ktxconverter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/**
 * KTX ↔ PNG 转换 ViewModel
 *
 * 支持模式:
 *   1. KTX→PNG (单文件)
 *   2. PNG→KTX (ETC2编码)
 *   3. 批量图集着色 + 切割 (KTX 图集用LUT着色 → 按Lua坐标切割成单独PNG)
 *
 * 着色方案 (来自游戏逆向分析):
 *   灰度R11_EAC KTX 图集的 G通道 = 亮度
 *   用颜色模板PNG构建 LUT: brightness→RGBA
 *   公式: result[p].RGB = LUT_RGB[gray[p].G], result[p].A = LUT_A[gray[p].G]
 */
class ConverterViewModel : ViewModel() {

    enum class ColorMode(val label: String) {
        GRAYSCALE("灰度原图"),
        GAME_LUT("有色斗篷"),
        PALETTE("182阶调色板"),
        ALL_IN_ONE("一键三连"),
    }
    data class UiState(
        val log: String = "等待操作...",
        val inputPath: String = "未选择文件",
        val isKtxToPng: Boolean = true,
        val colorMode: ColorMode = ColorMode.GRAYSCALE,
        val converting: Boolean = false,
        val atlasCutEnabled: Boolean = false,
        val luaContent: String? = null,
        val luaRegionCount: Int = -1,
        val templateLoaded: Boolean = false,
        val conversionDone: Boolean = false,
        val apkScanResult: ApkSourceManager.ScanResult? = null,
        val batchProgress: String = "",
        val saveToGallery: Boolean = false,
        val shizukuStatus: String = "",
        val atlasCutReady: Boolean = false,
        val isPaused: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var inputUri: Uri? = null
    private var apkUri: Uri? = null
    private var folderUri: Uri? = null
    private var folderKtxUris: List<Uri> = emptyList()
    private var luts: UiColorizer.Luts = UiColorizer.buildFineLut()
    private var palette: UiColorizer.Palette? = null
    private var templateLoaded = false
    /** 单文件模式转换结果缓存 */
    private var resultPixels: ByteArray? = null
    private var resultWidth: Int = 0
    private var resultHeight: Int = 0
    private var resultBaseName: String = ""

    /** 转换协程引用（支持停止） */
    private var conversionJob: Job? = null
    /** 用户自选输出文件夹 URI */
    private var selectedOutputUri: Uri? = null

    /** 并行线程数（根据CPU核心数自适应，不设上限） */
    private val parallelism: Int = maxOf(Runtime.getRuntime().availableProcessors(), 4)
    /** PVR原生管线互斥锁（防止并发写临时文件冲突） */
    private val pvrMutex = Mutex()

    /** 暂停/继续控制 */
    private val isPaused = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pauseGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** PNG切割重新开始：记住上次参数 */
    private var lastPngCutFiles: List<java.io.File>? = null
    private var lastPngCutContext: Context? = null

    /**
     * 带限流的并行遍历工具：最多 [maxParallel] 个协程同时执行 [block]。
     * 协程取消会自动传播到所有子任务。
     */
    private suspend fun <T> parallelForEach(
        items: List<T>,
        maxParallel: Int = parallelism,
        block: suspend (T) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(maxParallel)
        items.map { item ->
            async {
                semaphore.withPermit {
                    checkPause()
                    block(item)
                }
            }
        }.awaitAll()
    }

    /** 检查暂停状态，若已暂停则挂起等待恢复 */
    private suspend fun checkPause() {
        while (isPaused.get()) {
            val gate: kotlinx.coroutines.CompletableDeferred<Unit>
            synchronized(this) {
                gate = pauseGate ?: kotlinx.coroutines.CompletableDeferred<Unit>().also { pauseGate = it }
            }
            gate.await()
        }
    }

    private fun appendLog(msg: String) {
        _state.value = _state.value.copy(log = _state.value.log + "\n" + msg)
    }

    fun setMode(ktxToPng: Boolean) {
        _state.value = _state.value.copy(isKtxToPng = ktxToPng)
    }

    fun setColorMode(mode: ColorMode) {
        _state.value = _state.value.copy(colorMode = mode)
    }

    fun setAtlasCut(enabled: Boolean) {
        _state.value = _state.value.copy(atlasCutEnabled = enabled)
    }

    fun setSaveToGallery(gallery: Boolean) {
        _state.value = _state.value.copy(saveToGallery = gallery)
    }

    fun setOutputFolder(uri: Uri) {
        selectedOutputUri = uri
        appendLog("[OK] 已设置自选输出文件夹")
    }

    fun stopConversion() {
        // 若处于暂停状态，先解除暂停让协程能走到取消点
        if (isPaused.getAndSet(false)) {
            synchronized(this) {
                pauseGate?.complete(Unit)
                pauseGate = null
            }
        }
        conversionJob?.cancel()
        conversionJob = null
        lastPngCutFiles = null
        _state.value = _state.value.copy(converting = false, isPaused = false)
        appendLog("[已停止] 用户取消了转换")
    }

    /** 暂停/继续切换：暂停当前操作，再次点击从断点继续 */
    fun pauseResumeConversion() {
        if (isPaused.get()) {
            // 恢复
            isPaused.set(false)
            synchronized(this) {
                pauseGate?.complete(Unit)
                pauseGate = null
            }
            _state.value = _state.value.copy(isPaused = false)
            appendLog("[继续] 恢复工作...")
        } else {
            // 暂停
            isPaused.set(true)
            _state.value = _state.value.copy(isPaused = true)
            appendLog("[暂停] 已暂停，点击 ▶ 继续")
        }
    }

    /** 重新开始PNG切割（从头开始） */
    fun restartPngCut() {
        val ctx = lastPngCutContext ?: return
        val files = lastPngCutFiles ?: return
        // 先记下参数再停止（stopConversion 会清掉 lastPngCutFiles）
        val context = ctx
        val pngFiles = files.toList()
        stopConversion()
        // 重置状态并立即开始
        _state.value = _state.value.copy(converting = true, isPaused = false, log = "🔄 重新开始切割...")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200)  // 等旧协程 finally 执行完
            batchCutFromPngs(context, pngFiles)
        }
    }

    fun cancelAtlasCut() {
        _state.value = _state.value.copy(atlasCutReady = false)
    }

    fun confirmAtlasCut(context: Context) {
        _state.value = _state.value.copy(atlasCutReady = false)
        batchConvertAll(context)
    }

    /**
     * 从 assets 加载182阶调色板（与 Python ktx2png.py 相同）
     */
    fun setShizukuStatus(status: String) {
        _state.value = _state.value.copy(shizukuStatus = status)
    }

    fun initPalette(context: Context) {
        try {
            palette = UiColorizer.loadPalette(context)
            luts = UiColorizer.buildLutsFromPalette(palette!!)
            templateLoaded = true
            _state.value = _state.value.copy(templateLoaded = true)
            appendLog("[OK] 调色板已加载 (${palette!!.lums.size}阶)")
        } catch (e: Exception) {
            // 回退：模板PNG
            try {
                val stream = context.assets.open("颜色模板.png")
                val bytes = stream.use { it.readBytes() }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("无法解码模板")
                val w = bmp.width; val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                val rgba = ByteArray(w * h * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    rgba[i * 4] = ((p shr 16) and 0xFF).toByte()
                    rgba[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
                    rgba[i * 4 + 2] = (p and 0xFF).toByte()
                    rgba[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
                }
                bmp.recycle()
                luts = UiColorizer.buildLutsFromTemplate(rgba, w, h)
                templateLoaded = true
                _state.value = _state.value.copy(templateLoaded = true)
                appendLog("[OK] 颜色模板已加载 (${w}x${h})")
            } catch (e2: Exception) {
                appendLog("[警告] 调色板和模板加载均失败: ${e.message}")
            }
        }
    }

    /**
     * 从 assets 加载内置 UIPackedAtlas.lua 坐标文件
     */
    fun initLua(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = context.assets.open("UIPackedAtlas.lua").bufferedReader().use { it.readText() }
                val count = AtlasCutter.parseAtlasLua(content).size
                _state.value = _state.value.copy(
                    luaContent = content,
                    luaRegionCount = count
                )
                withContext(Dispatchers.Main) {
                    appendLog("[OK] 内置坐标已加载: $count 区域")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[警告] 内置Lua加载失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 加载颜色模板 PNG，构建 LUT
     */
    fun loadColorTemplate(uri: Uri, context: Context) {
        try {
            val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("无法读取模板")
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw Exception("无法解码模板图片")
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val rgba = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgba[i * 4] = ((p shr 16) and 0xFF).toByte()
                rgba[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
                rgba[i * 4 + 2] = (p and 0xFF).toByte()
                rgba[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
            }
            bitmap.recycle()

            luts = UiColorizer.buildLutsFromTemplate(rgba, w, h)
            templateLoaded = true
            _state.value = _state.value.copy(templateLoaded = true)
            appendLog("[OK] 颜色模板已加载, 构建LUT完成")
        } catch (e: Exception) {
            appendLog("[警告] 模板加载失败: ${e.message}, 使用内建精细LUT")
            luts = UiColorizer.buildFineLut()
        }
    }

    fun selectInput(uri: Uri, context: Context) {
        inputUri = uri
        val fileName = queryDisplayName(uri, context) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未知文件"
        _state.value = _state.value.copy(inputPath = fileName, log = "已选择: $fileName")
    }

    private fun queryDisplayName(uri: Uri, context: Context): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    fun selectLua(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: throw Exception("无法读取Lua")
                }
                // 后台线程解析，避免大文件卡主线程
                val count = withContext(Dispatchers.Default) {
                    AtlasCutter.parseAtlasLua(content).size
                }
                _state.value = _state.value.copy(
                    luaContent = content,
                    luaRegionCount = count
                )
                appendLog("[OK] Lua元数据已加载: $count 个区域")
            } catch (e: Exception) {
                appendLog("[错误] 读取Lua失败: ${e.message}")
            }
        }
    }

    fun convert(context: Context) {
        val uri = inputUri ?: run {
            appendLog("[错误] 请先选择文件")
            return
        }
        _state.value = _state.value.copy(converting = true, log = "开始转换...")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputData = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("无法读取文件")

                if (_state.value.isKtxToPng) {
                    if (_state.value.atlasCutEnabled && _state.value.luaContent != null) {
                        atlasCutMode(context, inputData)
                    } else {
                        ktxToPng(context, inputData)
                    }
                } else {
                    pngToKtx(context, inputData)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(converting = false, isPaused = false)
                    conversionJob = null
                }
            }
        }
    }

    /**
     * 扫描文件夹（SAF DocumentTree），采集所有 .ktx 文件
     * 对应原py中 batch_convert 的自动文件夹扫描逻辑
     */
    fun scanFolder(context: Context, uri: Uri) {
        folderUri = uri
        _state.value = _state.value.copy(log = "正在扫描文件夹...")
        viewModelScope.launch {
            try {
                val ktxList = withContext(Dispatchers.IO) {
                    val docDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                        ?: throw Exception("无法访问文件夹")
                    val files = docDir.listFiles()
                    val ktxFiles = mutableListOf<Uri>()
                    for (file in files) {
                        val name = file.name ?: continue
                        if (name.lowercase().endsWith(".ktx")) {
                            ktxFiles.add(file.uri)
                        }
                    }
                    ktxFiles
                }
                folderKtxUris = ktxList
                _state.value = _state.value.copy(
                    inputPath = "文件夹: ${ktxList.size} KTX",
                    log = "[OK] 文件夹扫描完成: ${ktxList.size} 个 KTX 文件"
                )
            } catch (e: Exception) {
                appendLog("[错误] 文件夹扫描失败: ${e.message}")
            }
        }
    }

    /**
     * 批量转换文件夹中的所有 KTX → PNG
     * 对应原py batch_convert() 逻辑
     */
    fun batchConvertFolder(context: Context) {
        if (folderKtxUris.isEmpty()) {
            appendLog("[错误] 请先选择包含 KTX 的文件夹")
            return
        }
        val outUri = folderUri
        val toGallery = _state.value.saveToGallery
        _state.value = _state.value.copy(converting = true, log = "")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 准备输出目录：优先自选文件夹 > 原文件夹 > 默认 Download
                val customDocDir = if (!toGallery && selectedOutputUri != null) {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, selectedOutputUri!!)?.also {
                        it.findFile(".nomedia") ?: it.createFile("application/octet-stream", ".nomedia")
                    }
                } else null
                val docDir = if (customDocDir != null) customDocDir
                    else if (outUri != null && !toGallery) {
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, outUri)
                    } else null
                val pngDir = docDir?.let { d ->
                    var sub = d.findFile("png")
                    if (sub == null) {
                        sub = d.createDirectory("png")
                        sub?.findFile(".nomedia") ?: sub?.createFile("application/octet-stream", ".nomedia")
                    }
                    sub
                }

                val total = folderKtxUris.size
                withContext(Dispatchers.Main) {
                    appendLog("══════ 文件夹批量转换: $total 个 KTX ══════")
                    when {
                        pngDir != null -> appendLog("  输出到所选文件夹/png/")
                        toGallery -> appendLog("  输出到相册 Pictures/KtxConverter/")
                        else -> appendLog("  输出到 Download/KtxConverter/")
                    }
                    appendLog("  并行线程: $parallelism")
                }

                val success = AtomicInteger(0)
                val fallbackDir = if (pngDir == null && !toGallery) {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/Folder").also {
                        it.mkdirs()
                        File(it, ".nomedia").createNewFile()
                    }
                } else null

                parallelForEach(folderKtxUris) { ktxUri ->
                    val name = ktxUri.lastPathSegment?.substringAfterLast('/') ?: "unknown.ktx"
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(batchProgress = "KTX→PNG: $name (${success.get() + 1}/$total)")
                    }
                    try {
                        val ktxData = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(ktxUri)?.use { it.readBytes() }
                        }
                        if (ktxData == null) {
                            withContext(Dispatchers.Main) { appendLog("  [跳过] 无法读取 $name") }
                            return@parallelForEach
                        }
                        val parsed = KtxParser.parse(ktxData)
                        val h = parsed.header
                        val rawPixels = pvrMutex.withLock {
                            tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight)
                        } ?: decodeKtxPixels(parsed)
                        val finalPixels = applyColorMode(rawPixels, h.pixelWidth, h.pixelHeight)

                        val baseName = name.removeSuffix(".ktx").removeSuffix(".KTX")
                        val fileName = "$baseName.png"

                        when {
                            pngDir != null -> savePngToDocDir(context, finalPixels, h.pixelWidth, h.pixelHeight, pngDir, fileName)
                            toGallery -> savePngToGallery(context, finalPixels, h.pixelWidth, h.pixelHeight, "KtxConverter/Folder", fileName)
                            else -> savePngToFile(finalPixels, h.pixelWidth, h.pixelHeight, File(fallbackDir!!, fileName))
                        }
                        success.incrementAndGet()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { appendLog("  [失败] $name: ${e.message}") }
                    }
                }

                val s = success.get()

                val location = when {
                    pngDir != null -> "所选文件夹/png/"
                    toGallery -> "相册"
                    else -> "Download/KtxConverter/"
                }
                withContext(Dispatchers.Main) {
                    appendLog("[完成] 文件夹批量: $s/$total → $location")
                    _state.value = _state.value.copy(conversionDone = true, batchProgress = "完成 $s/$total ✓")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(converting = false, isPaused = false)
                    conversionJob = null
                }
            }
        }
    }

    /** 保存 PNG 到 DocumentFile 目录 */
    private fun savePngToDocDir(context: Context, pixels: ByteArray, w: Int, h: Int, dir: androidx.documentfile.provider.DocumentFile, fileName: String) {
        val file = dir.findFile(fileName) ?: dir.createFile("image/png", fileName)
            ?: throw Exception("无法创建文件 $fileName")
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            pixelsToBitmap(pixels, w, h).compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw Exception("无法写入 $fileName")
    }

    // ──── APK 批量模式 ────

    /**
     * 扫描 APK 文件，自动识别 KTX、Lua
     */
    fun scanApkSource(uri: Uri, context: Context) {
        apkUri = uri
        _state.value = _state.value.copy(log = "正在扫描 APK...")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApkSourceManager.scanApk(context, uri)
                }
                val log = buildString {
                    appendLine("[OK] APK 扫描完成")
                    appendLine("  全部 KTX: ${result.allKtx.size} 个")
                    appendLine("  UIPackedAtlas 图集: ${result.atlasKtx.size} 个")
                    appendLine("  其他 KTX: ${result.otherKtx.size} 个")
                    appendLine("  Lua 坐标文件: ${if (result.luaEntry != null) "✓" else "✗ 未找到"}")
                    if (result.atlasKtx.isNotEmpty()) {
                        appendLine("  图集范围: ${result.atlasKtx.first().name} ~ ${result.atlasKtx.last().name}")
                    }
                }
                _state.value = _state.value.copy(
                    apkScanResult = result,
                    inputPath = "APK: ${result.atlasKtx.size + result.otherKtx.size} KTX + Lua",
                    log = log
                )
                // 使用内置 Lua（已由 initLua 预加载）
                if (_state.value.luaContent != null && result.atlasKtx.isNotEmpty()) {
                    _state.value = _state.value.copy(atlasCutReady = true)
                    appendLog("[提示] 内置坐标已就绪，请点击「开始转换」确认切割参数")
                } else if (result.luaEntry != null) {
                    // 回退：从 APK 提取 Lua
                    val luaData = withContext(Dispatchers.IO) {
                        ApkSourceManager.readEntry(context, uri, result.luaEntry)
                    }
                    if (luaData != null) {
                        val content = String(luaData)
                        val count = withContext(Dispatchers.Default) {
                            AtlasCutter.parseAtlasLua(content).size
                        }
                        _state.value = _state.value.copy(
                            luaContent = content,
                            luaRegionCount = count,
                            atlasCutReady = true
                        )
                        appendLog("[OK] UIPackedAtlas.lua 已加载: $count 区域")
                        appendLog("[提示] 请点击「开始转换」确认切割参数")
                    }
                }
            } catch (e: Exception) {
                appendLog("[错误] APK扫描失败: ${e.message}")
            }
        }
    }

    /**
     * 批量转换：图集切割 + 其他KTX解码
     */
    fun batchConvertAll(context: Context) {
        val scanResult = _state.value.apkScanResult ?: run {
            appendLog("[错误] 请先扫描 APK")
            return
        }
        val apkUri = this.apkUri ?: run {
            appendLog("[错误] APK 引用丢失，请重新选择")
            return
        }
        val saveToGallery = _state.value.saveToGallery

        _state.value = _state.value.copy(converting = true, log = "")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 输出目录：优先自选文件夹，否则默认 Download
                val customDocDir = if (!saveToGallery && selectedOutputUri != null) {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, selectedOutputUri!!)?.also {
                        it.findFile(".nomedia") ?: it.createFile("application/octet-stream", ".nomedia")
                    }
                } else null
                val outputBase = if (customDocDir != null) {
                    File(context.cacheDir, "KtxConverterBatch")
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/Batch")
                }
                outputBase.mkdirs()
                File(outputBase, ".nomedia").createNewFile()  // 防相册索引
                val saveToCustomDir = customDocDir != null && !saveToGallery

                // 阶段1: 图集切割
                if (scanResult.atlasKtx.isNotEmpty() && _state.value.luaContent != null) {
                    appendLog("══════ 阶段1: 图集切割 (${scanResult.atlasKtx.size} 个图集) ══════")
                    appendLog("  并行线程: $parallelism")
                    val regions = withContext(Dispatchers.Default) {
                        AtlasCutter.parseAtlasLua(_state.value.luaContent!!)
                    }
                    appendLog("  坐标区域总数: ${regions.size}")

                    // 预加载所有图集数据（一次遍历 ZIP，避免重复打开）
                    val atlasPaths = scanResult.atlasKtx.map { it.path }
                    val ktxCache = withContext(Dispatchers.IO) {
                        ApkSourceManager.readEntries(context, apkUri, atlasPaths)
                    }
                    appendLog("  预加载完成: ${ktxCache.size}/${atlasPaths.size} 个图集")

                    // 诊断：对比 Lua 与 APK 的图集名称
                    val luaAtlasNames = regions.map { it.atlas }.distinct().sorted()
                    val apkAtlasNames = scanResult.atlasKtx.map { it.name.removeSuffix(".ktx") }.sorted()
                    appendLog("  Lua图集名数: ${luaAtlasNames.size}, APK图集数: ${apkAtlasNames.size}")
                    val onlyLua = luaAtlasNames.toSet() - apkAtlasNames.toSet()
                    val onlyApk = apkAtlasNames.toSet() - luaAtlasNames.toSet()
                    if (onlyLua.isNotEmpty()) appendLog("  ⚠ 仅Lua有: ${onlyLua.take(5).joinToString()}${if (onlyLua.size>5) " (+${onlyLua.size-5})" else ""}")
                    if (onlyApk.isNotEmpty()) appendLog("  ⚠ 仅APK有: ${onlyApk.take(5).joinToString()}${if (onlyApk.size>5) " (+${onlyApk.size-5})" else ""}")
                    if (onlyLua.isEmpty() && onlyApk.isEmpty()) appendLog("  ✓ 所有图集名称匹配")

                    val totalCropped = AtomicInteger(0)
                    val atlasStats = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

                    // 保存路径: 相册用Pictures目录, 文件夹用私有目录
                    val uiOutputBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/UI")
                    uiOutputBase.mkdirs()
                    File(uiOutputBase, ".nomedia").createNewFile()  // 防相册索引

                    val skipSharpen = false

                    // ══════════════════════════════════════════
                    // Phase 1b: 并行切割 → 统一写入 __unclassified/（零锁）
                    // ══════════════════════════════════════════
                    val unclassifiedDir = File(uiOutputBase, "__unclassified")
                    unclassifiedDir.mkdirs()
                    File(unclassifiedDir, ".nomedia").createNewFile()

                    parallelForEach(scanResult.atlasKtx) { entry ->
                        val atlasName = entry.name.removeSuffix(".ktx")
                        _state.value = _state.value.copy(batchProgress = "图集切割: $atlasName (${totalCropped.get()} UI)")

                        val ktxData = ktxCache[entry.path]
                        if (ktxData == null) {
                            withContext(Dispatchers.Main) { appendLog("  [跳过] 无法读取 ${entry.name}") }
                            return@parallelForEach
                        }

                        val parsed = KtxParser.parse(ktxData)
                        val h = parsed.header
                        val rawPixels = pvrMutex.withLock {
                            tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight)
                        } ?: decodeKtxPixels(parsed)
                        val colored = applyColorMode(rawPixels, h.pixelWidth, h.pixelHeight)

                        val atlasRegions = regions.filter {
                            it.atlas == atlasName ||
                            it.atlas == entry.name ||
                            it.atlas.endsWith("/$atlasName") ||
                            it.atlas.endsWith("/${entry.name}")
                        }
                        if (atlasRegions.isEmpty()) {
                            withContext(Dispatchers.Main) { appendLog("  [跳过] $atlasName: 无匹配坐标区域") }
                            return@parallelForEach
                        }

                        var cropped = 0
                        for (region in atlasRegions) {
                            val crop = AtlasCutter.cropRegion(
                                colored, h.pixelWidth, h.pixelHeight,
                                region.u1, region.v1, region.u2, region.v2,
                                skipSharpen = skipSharpen
                            ) ?: continue

                            // Phase 1b: 写入 __unclassified/（本地文件系统，零锁）
                            savePngToFile(crop.pixels, crop.width, crop.height,
                                File(unclassifiedDir, "${region.name}.png"))
                            cropped++
                            atlasStats.getOrPut(AtlasCutter.classify(region.name)) { AtomicInteger(0) }.incrementAndGet()
                        }
                        totalCropped.addAndGet(cropped)
                        withContext(Dispatchers.Main) { appendLog("  $atlasName: $cropped 个UI") }
                    }

                    val tc = totalCropped.get()
                    appendLog("[切割完成] 共 $tc 个UI (Phase 1b)")

                    // ══════════════════════════════════════════
                    // Phase 2b: 分类归位
                    // ══════════════════════════════════════════
                    appendLog("[分类] 正在分类到 ${atlasStats.size} 个类别...")
                    val classifyStart = System.currentTimeMillis()

                    val uncFiles = unclassifiedDir.listFiles() ?: emptyArray()
                    if (uncFiles.isNotEmpty()) {
                        // 预建所有分类目录（避免 parallelForEach 内 synchronized 锁竞争）
                        val catDirs = mutableSetOf<String>()
                        for (file in uncFiles) {
                            catDirs.add(AtlasCutter.classify(file.nameWithoutExtension))
                        }
                        when {
                            saveToGallery -> { /* gallery 路径在 savePngToGallery 内部创建 */ }
                            saveToCustomDir -> {
                                for (cat in catDirs) {
                                    synchronized(this@ConverterViewModel) {
                                        var d = customDocDir!!.findFile(cat)
                                        if (d == null) {
                                            d = customDocDir.createDirectory(cat)
                                            d?.findFile(".nomedia") ?: d?.createFile("application/octet-stream", ".nomedia")
                                        }
                                    }
                                }
                            }
                            else -> {
                                for (cat in catDirs) {
                                    val d = File(uiOutputBase, cat)
                                    d.mkdirs()
                                    File(d, ".nomedia").createNewFile()
                                }
                            }
                        }
                        // 并行归类（无锁）
                        parallelForEach(uncFiles.toList()) { file ->
                            val name = file.nameWithoutExtension
                            val cat = AtlasCutter.classify(name)
                            when {
                                saveToGallery -> {
                                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                    if (bmp != null) {
                                        val px = IntArray(bmp.width * bmp.height)
                                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                                        val rgbaBytes = ByteArray(px.size * 4)
                                        for (i in px.indices) {
                                            val p = px[i]
                                            rgbaBytes[i*4] = ((p shr 16) and 0xFF).toByte()
                                            rgbaBytes[i*4+1] = ((p shr 8) and 0xFF).toByte()
                                            rgbaBytes[i*4+2] = (p and 0xFF).toByte()
                                            rgbaBytes[i*4+3] = ((p shr 24) and 0xFF).toByte()
                                        }
                                        bmp.recycle()
                                        savePngToGallery(context, rgbaBytes, bmp.width, bmp.height,
                                            "KtxConverter/UI/$cat", "${name}.png")
                                    }
                                    file.delete()
                                }
                                saveToCustomDir -> {
                                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                    if (bmp != null) {
                                        val px = IntArray(bmp.width * bmp.height)
                                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                                        val rgbaBytes = ByteArray(px.size * 4)
                                        for (i in px.indices) {
                                            val p = px[i]
                                            rgbaBytes[i*4] = ((p shr 16) and 0xFF).toByte()
                                            rgbaBytes[i*4+1] = ((p shr 8) and 0xFF).toByte()
                                            rgbaBytes[i*4+2] = (p and 0xFF).toByte()
                                            rgbaBytes[i*4+3] = ((p shr 24) and 0xFF).toByte()
                                        }
                                        val wBmp = bmp.width; val hBmp = bmp.height
                                        bmp.recycle()
                                        val catDir = customDocDir!!.findFile(cat)
                                        if (catDir != null) savePngToDocDir(context, rgbaBytes, wBmp, hBmp, catDir, "${name}.png")
                                    }
                                    file.delete()
                                }
                                else -> {
                                    val destFile = File(File(uiOutputBase, cat), file.name)
                                    if (!file.renameTo(destFile)) {
                                        file.copyTo(destFile, overwrite = true)
                                        file.delete()
                                    }
                                }
                            }
                        }
                    }

                    // 清理临时目录
                    unclassifiedDir.listFiles()?.forEach { it.delete() }
                    unclassifiedDir.delete()

                    val classifyMs = System.currentTimeMillis() - classifyStart
                    appendLog("[完成] 分类 ${tc} 个UI 耗时 ${classifyMs}ms")
                    for ((cat, count) in atlasStats.entries.sortedBy { it.key }) {
                        appendLog("  $cat: ${count.get()}")
                    }
                }

                // 阶段2: KTX直转
                if (scanResult.otherKtx.isNotEmpty()) {
                    appendLog("══════ 阶段2: KTX直转 (${scanResult.otherKtx.size} 个) ══════")
                    appendLog("  并行线程: $parallelism")
                    val decoded = AtomicInteger(0)
                    val decodedDir = if (!saveToGallery && !saveToCustomDir) {
                        File(outputBase, "Decoded").also {
                            it.mkdirs()
                            File(it, ".nomedia").createNewFile()
                        }
                    } else null

                    parallelForEach(scanResult.otherKtx) { entry ->
                        val baseName = entry.name.removeSuffix(".ktx")
                        _state.value = _state.value.copy(batchProgress = "KTX解码: $baseName (${decoded.get() + 1}/${scanResult.otherKtx.size})")

                        val ktxData = withContext(Dispatchers.IO) {
                            ApkSourceManager.readEntry(context, apkUri, entry.path)
                        }
                        if (ktxData == null) return@parallelForEach

                        val parsed = KtxParser.parse(ktxData)
                val h = parsed.header
                val rawPixels = pvrMutex.withLock {
                    tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight)
                } ?: decodeKtxPixels(parsed)
                val finalPixels = applyColorMode(rawPixels, h.pixelWidth, h.pixelHeight)

                if (saveToGallery) {
                            savePngToGallery(context, finalPixels, h.pixelWidth, h.pixelHeight,
                                "KtxConverter/Decoded", "$baseName.png")
                        } else if (saveToCustomDir) {
                            var dDir = customDocDir!!.findFile("Decoded")
                            if (dDir == null) dDir = customDocDir.createDirectory("Decoded")
                            if (dDir != null) savePngToDocDir(context, finalPixels, h.pixelWidth, h.pixelHeight, dDir, "$baseName.png")
                        } else {
                            savePngToFile(finalPixels, h.pixelWidth, h.pixelHeight, File(decodedDir!!, "$baseName.png"))
                        }
                        decoded.incrementAndGet()
                    }
                    appendLog("[完成] KTX解码: ${decoded.get()} 个")
                }

                val saveInfo = if (saveToGallery) "相册(Pictures/KtxConverter/)" else "私有文件夹(相册不可见)"
                appendLog("══════ 全部完成 ══════")
                appendLog("保存位置: $saveInfo")
                appendLog("输出目录: ${if (saveToGallery) "Pictures/KtxConverter/" else outputBase.absolutePath}")
                _state.value = _state.value.copy(
                    conversionDone = true,
                    batchProgress = "全部完成 ✓"
                )
            } catch (e: Exception) {
                appendLog("[错误] ${e.message}")
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(converting = false, isPaused = false)
                    conversionJob = null
                }
            }
        }
    }

    // ──── 一键三连模式 ────

    fun batchConvertAllInOne(context: Context) {
        val scanResult = _state.value.apkScanResult ?: run {
            appendLog("[错误] 请先扫描 APK"); return
        }
        val apkUri = this.apkUri ?: run {
            appendLog("[错误] APK 引用丢失"); return
        }
        _state.value = _state.value.copy(converting = true, log = "")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val outputBase = if (selectedOutputUri != null) {
                    // 自选文件夹用缓存目录过渡（最后通过 DocumentFile 写入）
                    File(context.cacheDir, "KtxConverterAllInOne")
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/AllInOne")
                }
                outputBase.mkdirs()
                File(outputBase, ".nomedia").createNewFile()  // 防相册索引
                val allKtx = scanResult.allKtx
                if (allKtx.isEmpty()) { appendLog("[错误] 无KTX"); return@launch }

                // 预加载所有 KTX
                val allPaths = allKtx.map { it.path }
                val ktxAllCache = withContext(Dispatchers.IO) {
                    ApkSourceManager.readEntries(context, apkUri, allPaths)
                }
                appendLog("══════ 一键三连: ${allKtx.size} 个 KTX ══════")
                appendLog("  预加载: ${ktxAllCache.size}/${allPaths.size}")
                appendLog("  并行线程: $parallelism")

                // Pass 1: 灰度原图
                appendLog("── Pass 1/3: 灰度原图 ──")
                val grayOk = AtomicInteger(0)
                val grayDir = File(outputBase, "Grayscale").also { it.mkdirs(); File(it, ".nomedia").createNewFile() }
                parallelForEach(allKtx) { entry ->
                    val name = entry.name.removeSuffix(".ktx")
                    _state.value = _state.value.copy(batchProgress = "灰度: $name (${grayOk.get() + 1}/${allKtx.size})")
                    val ktxData = ktxAllCache[entry.path] ?: return@parallelForEach
                    val parsed = KtxParser.parse(ktxData); val h = parsed.header
                    val pixels = pvrMutex.withLock {
                        tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight, "ktx2png_raw.py")
                    } ?: decodeKtxPixels(parsed)
                    savePngToFile(pixels, h.pixelWidth, h.pixelHeight, File(grayDir, "$name.png"))
                    grayOk.incrementAndGet()
                }
                appendLog("  灰度: ${grayOk.get()} OK")

                // Pass 2: 有色斗篷
                appendLog("── Pass 2/3: 有色斗篷 ──")
                val colorOk = AtomicInteger(0); val monoOk = AtomicInteger(0)
                val capeColorDir = File(outputBase, "Cape_Color").also { it.mkdirs(); File(it, ".nomedia").createNewFile() }
                val capeMonoDir = File(outputBase, "Cape_Mono").also { it.mkdirs(); File(it, ".nomedia").createNewFile() }
                parallelForEach(allKtx) { entry ->
                    val name = entry.name.removeSuffix(".ktx")
                    _state.value = _state.value.copy(batchProgress = "斗篷: $name (${colorOk.get() + monoOk.get() + 1}/${allKtx.size})")
                    val ktxData = ktxAllCache[entry.path] ?: return@parallelForEach
                    val parsed = KtxParser.parse(ktxData); val h = parsed.header
                    val isR11 = h.glInternalFormat == 0x9270 || h.glInternalFormat == 0x9271
                    val script = if (isR11) "ktx2png_color.py" else "ktx2png_raw.py"
                    val outDir = if (isR11) capeMonoDir else capeColorDir
                    val pixels = pvrMutex.withLock {
                        tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight, script)
                    } ?: decodeKtxPixels(parsed)
                    savePngToFile(pixels, h.pixelWidth, h.pixelHeight, File(outDir, "$name.png"))
                    if (isR11) monoOk.incrementAndGet() else colorOk.incrementAndGet()
                }
                appendLog("  有色: ${colorOk.get()} OK, 单色: ${monoOk.get()} OK")

                // Pass 3: 182调色板
                appendLog("── Pass 3/3: 182阶调色板 ──")
                val paletteOk = AtomicInteger(0)
                val paletteDir = File(outputBase, "Palette").also { it.mkdirs(); File(it, ".nomedia").createNewFile() }
                parallelForEach(allKtx) { entry ->
                    val name = entry.name.removeSuffix(".ktx")
                    _state.value = _state.value.copy(batchProgress = "调色板: $name (${paletteOk.get() + 1}/${allKtx.size})")
                    val ktxData = ktxAllCache[entry.path] ?: return@parallelForEach
                    val parsed = KtxParser.parse(ktxData); val h = parsed.header
                    val pixels = pvrMutex.withLock {
                        tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight, "ktx2png.py")
                    } ?: run {
                        val p = decodeKtxPixels(parsed)
                        applyColorMode(p, h.pixelWidth, h.pixelHeight)
                    }
                    savePngToFile(pixels, h.pixelWidth, h.pixelHeight, File(paletteDir, "$name.png"))
                    paletteOk.incrementAndGet()
                }
                appendLog("  调色板: ${paletteOk.get()} OK")
appendLog("══════ 一键三连 完成 ══════")
                appendLog("  Grayscale:  ${grayOk.get()}")
                appendLog("  Cape_Color: ${colorOk.get()}")
                appendLog("  Cape_Mono:  ${monoOk.get()}")
                appendLog("  Palette:    ${paletteOk.get()}")
                appendLog("输出: ${outputBase.absolutePath}")
                _state.value = _state.value.copy(conversionDone = true, batchProgress = "一键三连完成 ✓")
            } catch (e: Exception) {
                appendLog("[错误] ${e.message}"); e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(converting = false, isPaused = false)
                    conversionJob = null
                }
            }
        }
    }

    /**
     * 保存PNG到相册(MediaStore) - 只使用一级路径 Pictures/KtxConverter
     */
    private fun savePngToGallery(context: Context, pixels: ByteArray, w: Int, h: Int, subDir: String, fileName: String) {
        try {
            // 文件名包含子分类信息，RELATIVE_PATH只用一级
            val prefixedName = if (subDir != "KtxConverter") "${subDir.replace("/", "_")}_$fileName" else fileName
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, prefixedName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KtxConverter")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                appendLog("  [回退] MediaStore insert null → 存 Download")
                fallbackDownload(context, pixels, w, h, subDir, fileName)
                return
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                pixelsToBitmap(pixels, w, h).compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: run {
                appendLog("  [回退] openOutputStream null → 存 Download")
                fallbackDownload(context, pixels, w, h, subDir, fileName)
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            appendLog("  [回退] ${e.message} → 存 Download")
            fallbackDownload(context, pixels, w, h, subDir, fileName)
        }
    }

    /** 回退：保存到公共 Download 目录（文件管理器可见） */
    private fun fallbackDownload(context: Context, pixels: ByteArray, w: Int, h: Int, subDir: String, fileName: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/$subDir")
        dir.mkdirs()
        val file = File(dir, fileName)
        savePngToFile(pixels, w, h, file)
        // 通知相册扫描
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    }

    private fun decodeKtxPixels(parsed: KtxParser.ParsedKtx): ByteArray {
        val h = parsed.header
        return if (h.glType != 0) {
            EtcDecoder.decodeRawPixels(parsed.pixelData, h.pixelWidth, h.pixelHeight,
                h.glFormat, h.glType)
        } else {
            decodeCompressed(parsed.pixelData, h.pixelWidth, h.pixelHeight, h.glInternalFormat)
        }
    }

    /**
     * 尝试 PVR 原生解码 → 100%匹配 PVRTexTool
     * 回退: Kotlin EAC解码器 (82%)
     */
    private suspend fun tryPvrPipeline(
        context: Context, ktxBytes: ByteArray, @Suppress("UNUSED_PARAMETER") w: Int, @Suppress("UNUSED_PARAMETER") h: Int,
        scriptName: String = "ktx2png.py"
    ): ByteArray? {
        // ① ASTC 原生解码 (astcenc) — 速度最快，质量最高
        if (PvrKtxConverter.isAstcFormat(ktxBytes)) {
            try {
                val rgba = PvrKtxConverter.convertAstcToRgba(context, ktxBytes) { appendLog(it) }
                if (rgba != null) {
                    appendLog("[ASTC] OK astcenc 原生解码 100%")
                    return rgba
                }
                appendLog("[ASTC] 失败 → 回退PVR")
            } catch (e: Exception) {
                appendLog("[ASTC] 异常: ${e.message}")
            }
        }
        // ② PVR 原生解码 (PVRTexTool) — 带精修
        try {
            if (!PvrKtxConverter.isReady(context)) {
                if (!PvrKtxConverter.deploy(context) { appendLog(it) }) {
                    appendLog("[PVR] 部署失败 → 回退Kotlin")
                    return null
                }
            }
            val args = if (scriptName == "ktx2png.py") {
                val a = mutableListOf<String>()
                // PALETTE模式：App端着色，ktx2png.py只输出灰度
                // GAME_LUT模式：同上
                // GRAYSCALE模式：ktx2png.py自己着色
                if (_state.value.colorMode != ColorMode.GRAYSCALE) {
                    a.add("--gray")
                }
                a
            } else emptyList()
            val rgba = PvrKtxConverter.convertToRgba(context, ktxBytes, scriptName, args) { appendLog(it) }
            if (rgba != null) appendLog("[PVR] OK PVRTexTool 精致")
            else appendLog("[PVR] 转换失败 → 回退Kotlin")
            return rgba
        } catch (e: Exception) {
            appendLog("[PVR] 异常: ${e.message}")
            return null
        }
    }
    private suspend fun ktxToPng(context: Context, data: ByteArray) {
        appendLog("解析 KTX1 文件头...")
        val parsed = KtxParser.parse(data)
        val h = parsed.header
        appendLog("  格式: ${KtxParser.formatName(h.glInternalFormat)}")
        appendLog("  尺寸: ${h.pixelWidth} × ${h.pixelHeight}")

        // PVR 优先
    val rawPixels = tryPvrPipeline(context, data, h.pixelWidth, h.pixelHeight)
        ?: run {
            if (h.glType != 0) {
                EtcDecoder.decodeRawPixels(parsed.pixelData, h.pixelWidth, h.pixelHeight,
                    h.glFormat, h.glType)
            } else {
                decodeCompressed(parsed.pixelData, h.pixelWidth, h.pixelHeight, h.glInternalFormat)
            }
        }
    val finalPixels = applyColorMode(rawPixels, h.pixelWidth, h.pixelHeight)

    savePng(context, finalPixels, h.pixelWidth, h.pixelHeight)
    }

// 图集切割模式 ────
    private suspend fun atlasCutMode(context: Context, data: ByteArray) {
        appendLog("解析 KTX 图集...")
        val parsed = KtxParser.parse(data)
        val h = parsed.header
        appendLog("  格式: ${KtxParser.formatName(h.glInternalFormat)}, ${h.pixelWidth}×${h.pixelHeight}")

        // PVR 优先
        val colored = tryPvrPipeline(context, data, h.pixelWidth, h.pixelHeight)
            ?: run {
                val grayPixels = if (h.glType != 0) {
                    EtcDecoder.decodeRawPixels(parsed.pixelData, h.pixelWidth, h.pixelHeight,
                        h.glFormat, h.glType)
                } else {
                    decodeCompressed(parsed.pixelData, h.pixelWidth, h.pixelHeight, h.glInternalFormat)
                }
                appendLog("应用颜色模式...")
                applyColorMode(grayPixels, h.pixelWidth, h.pixelHeight)
            }

        // 解析 Lua 坐标
        val luaContent = _state.value.luaContent!!
        appendLog("解析 Lua 坐标...（内容长度: ${luaContent.length} 字符）")
        appendLog("  前150字: ${luaContent.take(150).replace("\n","\\n")}")
        val regions = AtlasCutter.parseAtlasLua(luaContent)
        appendLog("  找到 ${regions.size} 个 UI 区域")
        if (regions.isNotEmpty()) {
            val atlasNames = regions.map { it.atlas }.distinct().sorted()
            appendLog("  涉及图集: ${atlasNames.size} 个 (${atlasNames.take(5).joinToString()}${if (atlasNames.size>5) "..." else ""})")
        } else {
            appendLog("  ⚠ 解析结果为空！请检查 Lua 格式是否匹配正则")
        }

        // 切割
        val outputBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/UI切片")
        outputBase.mkdirs()
        File(outputBase, ".nomedia").createNewFile()
        var cropped = 0
        var skipped = 0
        val stats = mutableMapOf<String, Int>()

        val skipSharpen = false // 默认开启锐化（PS精修已删除）
        for (region in regions) {
            val crop = AtlasCutter.cropRegion(
                colored, h.pixelWidth, h.pixelHeight,
                region.u1, region.v1, region.u2, region.v2,
                skipSharpen = skipSharpen
            )
            if (crop == null) { skipped++; continue }

            val cat = AtlasCutter.classify(region.name)
            val outDir = File(outputBase, cat)
            outDir.mkdirs()
            File(outDir, ".nomedia").createNewFile()

            savePngToFile(crop.pixels, crop.width, crop.height, File(outDir, "${region.name}.png"))
            cropped++
            stats[cat] = (stats[cat] ?: 0) + 1
        }

        appendLog("[完成] 图集切割")
        appendLog("  切割: $cropped, 跳过: $skipped")
        appendLog("  输出: ${outputBase.absolutePath}")
        for ((cat, count) in stats.entries.sortedBy { it.key }) {
            appendLog("  $cat: $count 个")
        }
    }

    // ──── PNG图集批量切割（跳过KTX解码） ────

    /**
     * 在目录中递归扫描 UIPackedAtlas*.png 图集文件
     */
    fun scanPngAtlases(folder: File): List<File> {
        if (!folder.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        val queue = ArrayDeque<File>()
        queue.add(folder)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    queue.add(child)
                } else if (child.isFile && child.name.startsWith("UIPackedAtlas") &&
                    child.name.endsWith(".png", ignoreCase = true)) {
                    result.add(child)
                }
            }
        }
        return result.sortedBy { it.name }
    }

    /**
     * 从已转换的 PNG 图集批量切割
     * 前提：已加载 Lua 坐标
     */
    fun batchCutFromPngs(context: Context, pngFiles: List<File>) {
        if (pngFiles.isEmpty()) {
            appendLog("[错误] 未找到图集PNG文件")
            return
        }
        // 记住参数，供重新开始使用
        lastPngCutFiles = pngFiles.toList()
        lastPngCutContext = context.applicationContext ?: context
        val luaContent = _state.value.luaContent ?: run {
            appendLog("[错误] 请先加载 Lua 坐标文件")
            return
        }
        val saveToGallery = _state.value.saveToGallery
        _state.value = _state.value.copy(converting = true, log = "")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            try {
                appendLog("══════ PNG图集批量切割: ${pngFiles.size} 个图集 ══════")
                appendLog("  并行线程: $parallelism (CPU核心: ${Runtime.getRuntime().availableProcessors()})")
                // 解析 Lua（一次）
                val regions = withContext(Dispatchers.Default) {
                    AtlasCutter.parseAtlasLua(luaContent)
                }
                appendLog("  Lua区域总数: ${regions.size}")
                if (regions.isEmpty()) {
                    appendLog("[错误] Lua解析为空")
                    return@launch
                }

                // 输出目录
                val customDocDir = if (!saveToGallery && selectedOutputUri != null) {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, selectedOutputUri!!)?.also {
                        it.findFile(".nomedia") ?: it.createFile("application/octet-stream", ".nomedia")
                    }
                } else null
                val outputBase = if (customDocDir != null) {
                    File(context.cacheDir, "KtxConverterPngCut")
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/PNG切片")
                }
                outputBase.mkdirs()
                File(outputBase, ".nomedia").createNewFile()
                val saveToCustomDir = customDocDir != null && !saveToGallery
                // 诊断：输出模式
                appendLog("  输出模式: ${when {
                    saveToGallery -> "相册"
                    saveToCustomDir -> "自选文件夹 (${customDocDir!!.name})"
                    else -> "默认 (Download/KtxConverter/PNG切片/)"
                }}")

                // ══════════════════════════════════════════
                // Phase 1: 全图集并行切割 → 统一写入 __unclassified/（零锁）
                // ══════════════════════════════════════════
                val unclassifiedDir = File(outputBase, "__unclassified")
                unclassifiedDir.mkdirs()
                File(unclassifiedDir, ".nomedia").createNewFile()

                val totalCropped = AtomicInteger(0)
                val atlasStats = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
                val processed = AtomicInteger(0)
                val total = pngFiles.size

                parallelForEach(pngFiles) { pngFile ->
                    val atlasName = pngFile.name.removeSuffix(".png")
                    val idx = processed.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(batchProgress = "切割: $atlasName ($idx/$total)")
                    }

                    val bitmap = BitmapFactory.decodeFile(pngFile.absolutePath)
                    if (bitmap == null) {
                        withContext(Dispatchers.Main) { appendLog("  [跳过] 无法解码: ${pngFile.name}") }
                        return@parallelForEach
                    }
                    val w = bitmap.width; val h = bitmap.height
                    val argb = IntArray(w * h)
                    bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                    val rgba = ByteArray(w * h * 4)
                    for (i in argb.indices) {
                        val p = argb[i]
                        rgba[i*4] = ((p shr 16) and 0xFF).toByte()
                        rgba[i*4+1] = ((p shr 8) and 0xFF).toByte()
                        rgba[i*4+2] = (p and 0xFF).toByte()
                        rgba[i*4+3] = ((p shr 24) and 0xFF).toByte()
                    }
                    bitmap.recycle()

                    val atlasRegions = regions.filter {
                        it.atlas == atlasName ||
                        it.atlas == pngFile.name ||
                        it.atlas.endsWith("/$atlasName") ||
                        it.atlas.endsWith("/${pngFile.name}")
                    }
                    if (atlasRegions.isEmpty()) {
                        withContext(Dispatchers.Main) { appendLog("  [跳过] $atlasName: 无匹配坐标区域") }
                        return@parallelForEach
                    }

                    var cropped = 0
                    for (region in atlasRegions) {
                        val crop = AtlasCutter.cropRegion(
                            rgba, w, h, region.u1, region.v1, region.u2, region.v2,
                            skipSharpen = false
                        ) ?: continue

                        // Phase 1: 直接写到 __unclassified/（本地文件系统，极快，零锁）
                        savePngToFile(crop.pixels, crop.width, crop.height,
                            File(unclassifiedDir, "${region.name}.png"))
                        cropped++
                        atlasStats.getOrPut(AtlasCutter.classify(region.name)) { AtomicInteger(0) }.incrementAndGet()
                    }
                    totalCropped.addAndGet(cropped)
                    withContext(Dispatchers.Main) { appendLog("  $atlasName: $cropped 个UI") }
                }

                val tc = totalCropped.get()
                appendLog("[切割完成] 共 $tc 个UI (Phase 1)")

                // ══════════════════════════════════════════
                // Phase 2: 分类归位（按 category 移动到最终目录）
                // ══════════════════════════════════════════
                appendLog("[分类] 正在分类到 ${atlasStats.size} 个类别...")
                val classified = AtomicInteger(0)
                val classifyStart = System.currentTimeMillis()

                val uncFiles = unclassifiedDir.listFiles() ?: emptyArray()
                if (uncFiles.isNotEmpty()) {
                    // 预建所有分类目录（避免 parallelForEach 内 synchronized 锁竞争）
                    val catDirs = mutableSetOf<String>()
                    for (file in uncFiles) {
                        catDirs.add(AtlasCutter.classify(file.nameWithoutExtension))
                    }
                    when {
                        saveToGallery -> { /* 相册路径内部创建 */ }
                        saveToCustomDir -> {
                            for (cat in catDirs) {
                                synchronized(this@ConverterViewModel) {
                                    var d = customDocDir!!.findFile(cat)
                                    if (d == null) {
                                        d = customDocDir.createDirectory(cat)
                                        d?.findFile(".nomedia") ?: d?.createFile("application/octet-stream", ".nomedia")
                                    }
                                }
                            }
                        }
                        else -> {
                            for (cat in catDirs) {
                                val d = File(outputBase, cat)
                                d.mkdirs()
                                File(d, ".nomedia").createNewFile()
                            }
                        }
                    }
                    // 并行归类（无锁）
                    parallelForEach(uncFiles.toList()) { file ->
                        val name = file.nameWithoutExtension
                        val cat = AtlasCutter.classify(name)

                        when {
                            saveToGallery -> {
                                // 相册：读回像素 → MediaStore
                                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                if (bmp != null) {
                                    val px = IntArray(bmp.width * bmp.height)
                                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                                    val rgbaBytes = ByteArray(px.size * 4)
                                    for (i in px.indices) {
                                        val p = px[i]
                                        rgbaBytes[i*4] = ((p shr 16) and 0xFF).toByte()
                                        rgbaBytes[i*4+1] = ((p shr 8) and 0xFF).toByte()
                                        rgbaBytes[i*4+2] = (p and 0xFF).toByte()
                                        rgbaBytes[i*4+3] = ((p shr 24) and 0xFF).toByte()
                                    }
                                    bmp.recycle()
                                    savePngToGallery(context, rgbaBytes, bmp.width, bmp.height,
                                        "KtxConverter/PNG切片/$cat", "${name}.png")
                                }
                                file.delete()
                            }
                            saveToCustomDir -> {
                                // SAF：读回像素 → DocumentFile 写入
                                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                if (bmp != null) {
                                    val px = IntArray(bmp.width * bmp.height)
                                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                                    val rgbaBytes = ByteArray(px.size * 4)
                                    for (i in px.indices) {
                                        val p = px[i]
                                        rgbaBytes[i*4] = ((p shr 16) and 0xFF).toByte()
                                        rgbaBytes[i*4+1] = ((p shr 8) and 0xFF).toByte()
                                        rgbaBytes[i*4+2] = (p and 0xFF).toByte()
                                        rgbaBytes[i*4+3] = ((p shr 24) and 0xFF).toByte()
                                    }
                                    val wBmp = bmp.width; val hBmp = bmp.height
                                    bmp.recycle()
                                    val catDir = customDocDir!!.findFile(cat)
                                    if (catDir != null) savePngToDocDir(context, rgbaBytes, wBmp, hBmp, catDir, "${name}.png")
                                }
                                file.delete()
                            }
                            else -> {
                                // 默认模式：直接 renameTo（极快，纯文件系统操作）
                                val destFile = File(File(outputBase, cat), file.name)
                                if (!file.renameTo(destFile)) {
                                    // renameTo 跨文件系统可能失败，用复制+删除兜底
                                    file.copyTo(destFile, overwrite = true)
                                    file.delete()
                                }
                            }
                        }
                        val n = classified.incrementAndGet()
                        if (n % 200 == 0 || n == tc) {
                            withContext(Dispatchers.Main) {
                                _state.value = _state.value.copy(batchProgress = "分类: $n/$tc")
                            }
                        }
                    }
                }

                // 清理临时目录
                unclassifiedDir.listFiles()?.forEach { it.delete() }
                unclassifiedDir.delete()

                val classifyMs = System.currentTimeMillis() - classifyStart
                appendLog("[完成] 分类 ${classified.get()} 个UI 耗时 ${classifyMs}ms")
                for ((cat, count) in atlasStats.entries.sortedBy { it.key }) {
                    appendLog("  $cat: ${count.get()}")
                }
                appendLog("  输出: ${outputBase.absolutePath}")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    if (conversionJob == myJob) {
                        _state.value = _state.value.copy(converting = false, isPaused = false)
                        conversionJob = null
                    }
                }
            }
        }
    }

    // ──── 颜色处理分发 ────
    private fun applyColorMode(pixels: ByteArray, w: Int, h: Int): ByteArray = when (_state.value.colorMode) {
        ColorMode.GRAYSCALE -> {
            appendLog("  颜色: 灰度原图（无着色）")
            pixels
        }
        ColorMode.GAME_LUT -> {
            appendLog("  颜色: 精细LUT着色...")
            UiColorizer.colorize(pixels, luts, w, h)
        }
        ColorMode.PALETTE -> {
            val p = palette
            if (p != null) {
                appendLog("  颜色: 182阶调色板着色...")
                UiColorizer.colorizeWithPalette(pixels, p, w, h)
            } else if (templateLoaded) {
                appendLog("  颜色: 模板LUT着色...")
                UiColorizer.colorize(pixels, luts, w, h)
            } else {
                appendLog("  [回退] 调色板未加载，使用灰度原图")
                pixels
            }
        }
        ColorMode.ALL_IN_ONE -> {
            appendLog("  颜色: 一键三连（使用PVR管线）")
            pixels  // 三连模式走 PVR，此处仅回退
        }
    }

    private fun decodeCompressed(data: ByteArray, w: Int, h: Int, fmt: Int): ByteArray = when (fmt) {
        // RGB (线性+sRGB → 同一解码器)
        KtxParser.GL_COMPRESSED_RGB8_ETC2,
        KtxParser.GL_COMPRESSED_SRGB8_ETC2 -> EtcDecoder.decodeEtc2Rgb(data, w, h)
        // RGBA (线性+sRGB → 同一解码器)
        KtxParser.GL_COMPRESSED_RGBA8_ETC2_EAC,
        KtxParser.GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC -> EtcDecoder.decodeEtc2Rgba(data, w, h)
        // 单通道 (UI图集)
        KtxParser.GL_COMPRESSED_R11_EAC -> EtcDecoder.decodeR11Eac(data, w, h)
        // 以下格式光遇未使用，保留以备其他游戏
        KtxParser.GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2,
        KtxParser.GL_COMPRESSED_SRGB8_PUNCHTHROUGH_ALPHA1_ETC2 -> EtcDecoder.decodeEtc2Rgb(data, w, h)
        KtxParser.GL_COMPRESSED_RG11_EAC -> EtcDecoder.decodeRg11Eac(data, w, h)
        KtxParser.GL_COMPRESSED_SIGNED_R11_EAC -> EtcDecoder.decodeR11Eac(data, w, h)
        KtxParser.GL_COMPRESSED_SIGNED_RG11_EAC -> EtcDecoder.decodeRg11Eac(data, w, h)
        KtxParser.GL_ETC1_RGB8 -> EtcDecoder.decodeEtc2Rgb(data, w, h)
        else -> throw Exception("不支持的压缩格式: ${KtxParser.formatName(fmt)}")
    }

    // ──── PNG 保存 ────
    private fun storeResult(pixels: ByteArray, w: Int, h: Int) {
        resultPixels = pixels
        resultWidth = w
        resultHeight = h
        resultBaseName = _state.value.inputPath.removeSuffix(".ktx").removeSuffix(".KTX")
        _state.value = _state.value.copy(conversionDone = true)
        appendLog("[完成] 转换成功，请选择保存方式")
    }

    fun saveToFolder(context: Context, folderUri: Uri) {
        val pixels = resultPixels ?: return
        try {
            val docDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                ?: throw Exception("无法访问文件夹")
            val suffix = when (_state.value.colorMode) {
                ColorMode.GAME_LUT -> "_colored"
                ColorMode.PALETTE -> "_enhanced"
                else -> ""
            }
            val fileName = "${resultBaseName}${suffix}.png"
            val file = docDir.createFile("image/png", fileName)
                ?: throw Exception("无法创建文件")
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                pixelsToBitmap(pixels, resultWidth, resultHeight).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // 创建 .nomedia 防止相册扫描
            val nomedia = docDir.createFile("application/octet-stream", ".nomedia")
            nomedia?.let {
                context.contentResolver.openOutputStream(it.uri)?.use { /* 空文件 */ }
            }
            appendLog("[OK] 已保存到文件夹: $fileName (+ .nomedia)")
        } catch (e: Exception) {
            appendLog("[错误] 保存失败: ${e.message}")
        }
    }

    fun saveToGallery(context: Context) {
        val pixels = resultPixels ?: return
        try {
            val suffix = when (_state.value.colorMode) {
                ColorMode.GAME_LUT -> "_colored"
                ColorMode.PALETTE -> "_enhanced"
                else -> ""
            }
            val fileName = "${resultBaseName}${suffix}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KtxConverter")
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("无法写入相册")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                pixelsToBitmap(pixels, resultWidth, resultHeight).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            appendLog("[OK] 已保存到相册: Pictures/KtxConverter/$fileName")
        } catch (e: Exception) {
            appendLog("[错误] 保存到相册失败: ${e.message}")
        }
    }

    private fun pixelsToBitmap(pixels: ByteArray, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val intBuf = IntArray(w * h)
        for (i in intBuf.indices) {
            val a = pixels[i * 4 + 3].toInt() and 0xFF
            val r = pixels[i * 4].toInt() and 0xFF
            val g = pixels[i * 4 + 1].toInt() and 0xFF
            val b = pixels[i * 4 + 2].toInt() and 0xFF
            intBuf[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(intBuf, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun savePng(@Suppress("UNUSED_PARAMETER") context: Context, pixels: ByteArray, w: Int, h: Int) {
        storeResult(pixels, w, h)
    }

    private fun savePngToFile(pixels: ByteArray, w: Int, h: Int, file: File) {
        pixelsToBitmap(pixels, w, h).let { bitmap ->
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    // ──── PNG→KTX ────
    private fun pngToKtx(context: Context, data: ByteArray) {
        appendLog("解码 PNG...")
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw Exception("无法解码 PNG 文件")
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgba = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgba[i * 4] = ((p shr 16) and 0xFF).toByte()
            rgba[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
            rgba[i * 4 + 2] = (p and 0xFF).toByte()
            rgba[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
        }
        bitmap.recycle()

        val hasAlpha = EtcEncoder.detectAlpha(rgba)
        val pw = EtcEncoder.pad4(w)
        val ph = EtcEncoder.pad4(h)
        val bgra = EtcEncoder.rgbaToBgra(rgba, w, h, pw, ph, hasAlpha)
        val compressed = if (hasAlpha) EtcEncoder.compressEtc2Rgba(bgra, pw, ph)
                         else EtcEncoder.compressEtc2Rgb(bgra, pw, ph)
        val ktx = EtcEncoder.buildKtx1(w, h, compressed, hasAlpha)

        val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KtxConverter")
        outputDir.mkdirs()
        val inputFileName = _state.value.inputPath.removeSuffix(".png").removeSuffix(".PNG")
        val outputFile = File(outputDir, "${inputFileName}.ktx")
        FileOutputStream(outputFile).use { it.write(ktx) }

        val fmt = if (hasAlpha) "ETC2_RGBA8_EAC" else "ETC2_RGB8"
        appendLog("[完成] ${outputFile.absolutePath}")
        appendLog("  格式: $fmt, 大小: ${ktx.size} bytes")
    }

    /**
     * 纯KTX→PNG：跳过UIPackedAtlas*，不用调色板，不用切割。
     * 直接读取文件夹中所有 .ktx → PVR原生解码(kts2png_raw.py) → 纯灰度PNG。
     */
    fun simpleRawConvert(context: Context, folderPath: String) {
        _state.value = _state.value.copy(converting = true, log = "")
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val srcDir = File(folderPath)
                if (!srcDir.exists() || !srcDir.isDirectory) {
                    withContext(Dispatchers.Main) { appendLog("[错误] 无效目录: $folderPath") }
                    return@launch
                }

                // 安全递归扫描 .ktx（不用 walkTopDown，避免 Android 权限/遍历异常）
                val ktxFiles = mutableListOf<File>()
                try {
                    collectKtxFiles(srcDir, ktxFiles)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { appendLog("[错误] 扫描失败: ${e.message}") }
                    return@launch
                }

                if (ktxFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendLog("[提示] 未找到 .ktx 文件")
                        appendLog("  路径: $folderPath")
                        appendLog("  (已排除UIPackedAtlas*，检查确认文件夹内有 .ktx)")
                    }
                    return@launch
                }

                val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KtxConverter/Raw")
                outputDir.mkdirs()
                File(outputDir, ".nomedia").createNewFile()

                val total = ktxFiles.size
                withContext(Dispatchers.Main) {
                    appendLog("══════ 纯KTX→PNG: $total 个文件 ══════")
                    appendLog("  输入: $folderPath")
                    appendLog("  输出: ${outputDir.absolutePath}")
                    for (f in ktxFiles.take(10)) appendLog("    ${f.name}")
                    if (ktxFiles.size > 10) appendLog("    ... 还有 ${ktxFiles.size - 10} 个")
                }

                val success = AtomicInteger(0)
                val lastUiUpdate = AtomicInteger(0) // 节流：每5文件或200ms更新

                parallelForEach(ktxFiles) { file ->
                    val name = file.name
                    val cur = success.get() + 1
                    val last = lastUiUpdate.get()
                    // 节流：每 5 个文件更新一次进度条，避免主线程抖动
                    if (cur - last >= 5 || cur == total) {
                        if (lastUiUpdate.compareAndSet(last, cur)) {
                            _state.value = _state.value.copy(batchProgress = "纯KTX→PNG: $name ($cur/$total)")
                        }
                    }
                    try {
                        val ktxData = file.readBytes()
                        val parsed = KtxParser.parse(ktxData)
                        val h = parsed.header

                        val finalPixels = pvrMutex.withLock {
                            tryPvrPipeline(context, ktxData, h.pixelWidth, h.pixelHeight, "ktx2png_raw.py")
                        } ?: throw Exception("PVR解码失败")

                        val baseName = name.removeSuffix(".ktx").removeSuffix(".KTX")
                        val outFile = File(outputDir, "$baseName.png")
                        savePngToFile(finalPixels, h.pixelWidth, h.pixelHeight, outFile)
                        success.incrementAndGet()
                    } catch (e: Exception) {
                        // 失败日志缓冲，最后统一输出避免主线程抖动
                        withContext(Dispatchers.Main) { appendLog("  [失败] $name: ${e.message}") }
                    }
                }

                val s = success.get()
                withContext(Dispatchers.Main) {
                    appendLog("[完成] 纯KTX→PNG: $s/$total")
                    _state.value = _state.value.copy(conversionDone = true, batchProgress = "完成 $s/$total ✓")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(converting = false, isPaused = false)
                    conversionJob = null
                }
            }
        }
    }

    /** 递归收集 .ktx 文件，跳过 UIPackedAtlas* */
    private fun collectKtxFiles(dir: File, out: MutableList<File>, depth: Int = 0) {
        if (depth > 8) return // 安全限深
        val children = dir.listFiles() ?: return
        for (f in children) {
            try {
                if (f.isFile && f.name.lowercase().endsWith(".ktx") && !f.name.startsWith("UIPackedAtlas")) {
                    out.add(f)
                } else if (f.isDirectory) {
                    collectKtxFiles(f, out, depth + 1)
                }
            } catch (_: Exception) { /* 跳过不可读文件 */ }
        }
    }
}