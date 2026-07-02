package com.ktxconverter.app

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ktxconverter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm = ConverterViewModel()

    private var sourceMode = "apk"
    private var saveToGallery = false
    private var lastConversionDone = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.selectInput(it, this) } }

    private val luaPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.selectLua(it, this) } }

    /** PNG图集自选文件夹选择器 */
    private lateinit var pngFolderPicker: androidx.activity.result.ActivityResultLauncher<Uri?>
    /** 暂存PNG切割输入路径，供输出选择后重新弹确认窗 */
    private var pendingPngCutPath: String? = null

    /** 纯KTX→PNG 文件夹选择器 */
    private lateinit var rawFolderPicker: androidx.activity.result.ActivityResultLauncher<Uri?>

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.scanApkSource(it, this) } }

    private lateinit var folderPicker: androidx.activity.result.ActivityResultLauncher<Uri?>

    /** 输出目录选择器（单张转换后选择保存文件夹） */
    private val outputDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.saveToFolder(this, uri)
        }
    }

    /** 自选输出文件夹（批量模式用） */
    private val customOutputPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            vm.setOutputFolder(uri)
            b.tvSaveHint.text = "当前: 📁 自选文件夹"
            Toast.makeText(this, "已选择自选输出文件夹", Toast.LENGTH_SHORT).show()
            // 如果是PNG切割流程中选的，重新弹确认窗
            pendingPngCutPath?.let { path ->
                pendingPngCutPath = null
                scanAndConfirmPngCut(path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // 复制日志按钮
        b.btnCopyLog.setOnClickListener {
            val text = b.tvLog.text?.toString() ?: ""
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("日志", text))
            Toast.makeText(this, "日志已复制 (${text.length}字)", Toast.LENGTH_SHORT).show()
        }
        // 日志折叠/展开
        var logExpanded = false
        b.llLogHeader.setOnClickListener {
            logExpanded = !logExpanded
            if (logExpanded) {
                b.tvLogToggle.text = "▼ 日志"
                b.svLog.visibility = View.VISIBLE
                val lp = b.svLog.layoutParams
                lp.height = (280 * resources.displayMetrics.density).toInt()
                b.svLog.layoutParams = lp
            } else {
                b.tvLogToggle.text = "▶ 日志"
                b.svLog.visibility = View.GONE
            }
        }
        vm.initPalette(this)
        vm.initLua(this)
        vm.setColorMode(ConverterViewModel.ColorMode.PALETTE)
        checkStoragePermission()
        initFolderPicker()
        initPngFolderPicker()
        initRawFolderPicker()
        setupSourceButtons()
        setupSaveButtons()
        setupAuxButtons()
        setupConvertButton()
        observeState()
        refreshButtonStates()

        // ✦ 光遇动态效果：全按钮呼吸脉冲 + 光泽扫过（React Bits Glare Hover）+ 星空粒子
        PulseAnimator.applyPulse(b.btnConvert, maxScale = 1.03f)   // 主按钮
        PulseAnimator.applyPulse(b.btnSourceSingle)                 // 文件来源
        PulseAnimator.applyPulse(b.btnSourceApk)
        PulseAnimator.applyPulse(b.btnSourcePick)
        PulseAnimator.applyPulse(b.btnSourceFolder)
        PulseAnimator.applyPulse(b.btnSaveFolder)                   // 保存位置
        PulseAnimator.applyPulse(b.btnSaveGallery)
        PulseAnimator.applyPulse(b.btnSaveCustom)
        PulseAnimator.applyPulse(b.btnAtlasCut)                     // 工具
        PulseAnimator.applyPulse(b.btnLoadLua)
        PulseAnimator.applyPulse(b.btnPngCut)
        PulseAnimator.applyPulse(b.btnRawConvert)
        PulseAnimator.applyPulse(b.btnStop)                         // 停止/重来
        PulseAnimator.applyPulse(b.btnRestart)
        PulseAnimator.applyPulse(b.btnCopyLog, maxScale = 1.02f)    // 复制按钮（小幅）
        // React Bits Glare Hover：按下时对角线光泽扫过
        GlareSweepEffect.applyGlare(b.btnConvert, glareAlpha = 0.25f)
        GlareSweepEffect.applyGlare(b.btnSourceSingle, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnSourceApk, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnSourcePick, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnSourceFolder, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnSaveFolder, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnSaveGallery, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnSaveCustom, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnAtlasCut, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnLoadLua, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnPngCut, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnRawConvert, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnStop, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnRestart, glareAlpha = 0.22f)
        GlareSweepEffect.applyGlare(b.btnCopyLog, glareAlpha = 0.18f)
        GlareSweepEffect.applyGlare(b.btnBrowseFile, glareAlpha = 0.18f)
        b.stardustOverlay.startAnimation()

        // 🥚 彩蛋：点击 sky_logotext 播放彩蛋音频
        b.ivSkyLogo.setOnClickListener {
            try {
                val mp = android.media.MediaPlayer.create(this, R.raw.kunkun)
                mp?.setOnCompletionListener { it.release() }
                mp?.start()
                Toast.makeText(this, "🐔 哎～哟～", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        // 暂停/继续按钮（单击暂停/继续，长按硬停止）
        b.btnStop.setOnClickListener {
            vm.pauseResumeConversion()
        }
        b.btnStop.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认停止")
                .setMessage("确定要完全停止当前操作吗？\n（暂停模式下会先恢复再停止）")
                .setPositiveButton("停止") { _, _ ->
                    vm.stopConversion()
                    Toast.makeText(this, "已停止转换", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        // 重新开始按钮（仅PNG切割时可用）
        b.btnRestart.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("🔄 重新开始")
                .setMessage("确定要终止当前切割并从头开始？")
                .setPositiveButton("确定") { _, _ ->
                    vm.restartPngCut()
                    Toast.makeText(this, "正在重新开始...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun appendLog(msg: String) {
        b.tvLog.text = (b.tvLog.text?.toString() ?: "") + "\n" + msg
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun initFolderPicker() {
        folderPicker = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                vm.scanFolder(this, uri)
            }
        }
    }

    private fun initPngFolderPicker() {
        pngFolderPicker = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                val path = getPathFromTreeUri(uri)
                if (path != null) {
                    scanAndConfirmPngCut(path)
                } else {
                    Toast.makeText(this, "无法解析文件夹路径", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initRawFolderPicker() {
        rawFolderPicker = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                val path = getPathFromTreeUri(uri)
                if (path != null) {
                    vm.simpleRawConvert(this, path)
                } else {
                    Toast.makeText(this, "无法解析文件夹路径", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSourceButtons() {
        b.btnSourceSingle.setOnClickListener {
            sourceMode = "single"
            b.llFilePicker.visibility = View.VISIBLE
            refreshButtonStates()
        }
        b.btnSourceApk.setOnClickListener {
            sourceMode = "apk"
            b.llFilePicker.visibility = View.GONE
            refreshButtonStates()
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }
        b.btnSourcePick.setOnClickListener {
            sourceMode = "pick"
            b.llFilePicker.visibility = View.GONE
            refreshButtonStates()
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }
        b.btnSourceFolder.setOnClickListener {
            sourceMode = "folder"
            b.llFilePicker.visibility = View.GONE
            refreshButtonStates()
            folderPicker.launch(null)
        }
        b.btnBrowseFile.setOnClickListener {
            if (sourceMode == "single") filePicker.launch(arrayOf("*/*"))
            else apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }
    }

    private fun setupSaveButtons() {
        b.btnSaveFolder.setOnClickListener {
            saveToGallery = false
            vm.setSaveToGallery(false)
            b.tvSaveHint.text = "当前: 文件夹模式 (Download/KtxConverter/)"
            refreshButtonStates()
            Toast.makeText(this, "已选: 文件夹保存", Toast.LENGTH_SHORT).show()
        }
        b.btnSaveGallery.setOnClickListener {
            saveToGallery = true
            vm.setSaveToGallery(true)
            b.tvSaveHint.text = "当前: 相册模式 (Pictures/KtxConverter/)"
            refreshButtonStates()
            Toast.makeText(this, "已选: 相册保存", Toast.LENGTH_SHORT).show()
        }
        b.btnSaveCustom.setOnClickListener {
            saveToGallery = false
            vm.setSaveToGallery(false)
            customOutputPicker.launch(null)
        }
    }

    private fun setupAuxButtons() {
        b.btnAtlasCut.setOnClickListener {
            val newState = !vm.state.value.atlasCutEnabled
            vm.setAtlasCut(newState)
            val text = if (newState) "✂ 切割: 开" else "✂ 切割: 关"
            b.btnAtlasCut.text = text
            Toast.makeText(this, if (newState) "图集切割已开启" else "图集切割已关闭", Toast.LENGTH_SHORT).show()
            refreshButtonStates()
        }
        b.btnPngCut.setOnClickListener {
            if (vm.state.value.luaRegionCount <= 0) {
                AlertDialog.Builder(this)
                    .setTitle("需要Lua坐标")
                    .setMessage("请先加载 UIPackedAtlas.lua（通过扫描APK或手动选择Lua文件）")
                    .setPositiveButton("手动选择Lua") { _, _ -> luaPicker.launch(arrayOf("*/*")) }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            showPngCutSourceDialog()
        }
        b.btnLoadLua.setOnClickListener { luaPicker.launch(arrayOf("*/*")) }
        b.btnRawConvert.setOnClickListener { showRawConvertDialog() }
    }

    private fun setupConvertButton() {
        b.btnConvert.setOnClickListener {
            val isApkMode = (sourceMode == "apk" || sourceMode == "pick")
            if (isApkMode && vm.state.value.atlasCutReady) {
                // APK扫描完成：显示切割确认弹窗
                showAtlasCutConfirmDialog()
                return@setOnClickListener
            }
            val desc = when (sourceMode) {
                "single" -> "单张KTX"
                "folder" -> "文件夹批量"
                "apk" -> "APK批量"
                else -> "选几张"
            }
            val saveDesc = if (saveToGallery) "相册" else "文件夹"
            val msg = "来源: $desc\n颜色: 182阶调色板\n保存: $saveDesc\n\n开始转换？"
            AlertDialog.Builder(this@MainActivity)
                .setTitle("确认转换")
                .setMessage(msg)
                .setPositiveButton("确定") { _, _ -> doConvert() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showAtlasCutConfirmDialog() {
        val scan = vm.state.value.apkScanResult ?: return
        val regionCount = vm.state.value.luaRegionCount
        val atlasCount = scan.atlasKtx.size
        val otherCount = scan.otherKtx.size
        val saveDesc = if (saveToGallery) "相册(Pictures/KtxConverter/)" else "文件夹(Download/KtxConverter/)"
        val msg = buildString {
            appendLine("UIPackedAtlas 解析完成，即将开始切割：")
            appendLine()
            appendLine("  图集文件: $atlasCount 个")
            appendLine("  UI区域总数: $regionCount 个")
            if (otherCount > 0) appendLine("  其他KTX: $otherCount 个（将直接解码）")
            appendLine()
            appendLine("  输出到: $saveDesc")
            appendLine("  自动按类型分30+子目录")
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle("确认切割")
            .setMessage(msg)
            .setPositiveButton("开始切割") { _, _ ->
                vm.confirmAtlasCut(this)
            }
            .setNegativeButton("取消") { _, _ ->
                vm.cancelAtlasCut()
            }
            .show()
    }

    /** 纯KTX→PNG：先弹窗选默认或自定义路径 */
    private fun showRawConvertDialog() {
        val defaultPath = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
        val view = layoutInflater.inflate(R.layout.dialog_png_cut, null)
        val dialog = AlertDialog.Builder(this@MainActivity, R.style.SkyDialogTheme)
            .setView(view)
            .create()
        // 修改标题和文字（optionDefault/optionCustom 是 LinearLayout，取其内部 TextView）
        view.findViewById<View>(R.id.optionDefault)?.let { layout ->
            (layout as? android.view.ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i)
                    if (child is android.view.ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val sub = child.getChildAt(j)
                            if (sub is android.widget.TextView) {
                                if (j == 0) sub.text = "默认: Download"
                                else sub.text = defaultPath
                            }
                        }
                    }
                }
            }
        }
        view.findViewById<View>(R.id.optionCustom)?.let { layout ->
            (layout as? android.view.ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i)
                    if (child is android.view.ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val sub = child.getChildAt(j)
                            if (sub is android.widget.TextView) {
                                if (j == 0) sub.text = "其他文件夹..."
                                else sub.text = "从任意位置选择 KTX 文件夹"
                            }
                        }
                    }
                }
            }
        }
        view.findViewById<View>(R.id.optionDefault).setOnClickListener {
            dialog.dismiss()
            vm.simpleRawConvert(this, defaultPath)
        }
        view.findViewById<View>(R.id.optionCustom).setOnClickListener {
            dialog.dismiss()
            rawFolderPicker.launch(null)
        }
        view.findViewById<View>(R.id.optionCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.let { win ->
            val dm = resources.displayMetrics
            win.setLayout((dm.widthPixels * 0.88).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showPngCutSourceDialog() {
        val defaultPath = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath + "/KtxConverter"
        val view = layoutInflater.inflate(R.layout.dialog_png_cut, null)
        val dialog = AlertDialog.Builder(this@MainActivity, R.style.SkyDialogTheme)
            .setView(view)
            .create()
        view.findViewById<View>(R.id.optionDefault).setOnClickListener {
            dialog.dismiss()
            scanAndConfirmPngCut(defaultPath)
        }
        view.findViewById<View>(R.id.optionCustom).setOnClickListener {
            dialog.dismiss()
            pngFolderPicker.launch(null)
        }
        view.findViewById<View>(R.id.optionCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        // 调整弹窗宽度
        dialog.window?.let { win ->
            val dm = resources.displayMetrics
            win.setLayout((dm.widthPixels * 0.88).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 扫描指定路径下的图集PNG，弹窗确认后开始切割 */
    private fun scanAndConfirmPngCut(folderPath: String) {
        val folder = java.io.File(folderPath)
        if (!folder.isDirectory) {
            AlertDialog.Builder(this)
                .setTitle("路径无效")
                .setMessage("$folderPath\n不是有效目录")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        val pngFiles = vm.scanPngAtlases(folder)
        if (pngFiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未找到图集PNG")
                .setMessage("在 $folderPath 及其子目录中\n未找到 UIPackedAtlas*.png 文件")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        val regionCount = vm.state.value.luaRegionCount
        // 根据当前保存模式显示实际输出路径
        val saveDesc = when {
            saveToGallery -> "相册 (Pictures/KtxConverter/PNG切片/)"
            else -> {
                val customHint = b.tvSaveHint.text?.toString() ?: ""
                if (customHint.contains("自选")) "📁 自选文件夹"
                else "文件夹 (Download/KtxConverter/PNG切片/)"
            }
        }
        val msg = buildString {
            appendLine("找到 ${pngFiles.size} 个图集PNG：")
            appendLine()
            for (f in pngFiles.take(8)) {
                appendLine("  • ${f.name}")
            }
            if (pngFiles.size > 8) appendLine("  ... 还有 ${pngFiles.size - 8} 个")
            appendLine()
            appendLine("Lua区域总数: $regionCount 个")
            appendLine("输出到: $saveDesc")
            appendLine("自动按类型分目录 + .nomedia")
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle("🖼 确认批量切割")
            .setMessage(msg)
            .setPositiveButton("开始切割 (${pngFiles.size}个)") { _, _ ->
                vm.batchCutFromPngs(this, pngFiles)
            }
            .setNeutralButton("📁 选择输出文件夹") { _, _ ->
                pendingPngCutPath = folderPath
                customOutputPicker.launch(null)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从tree URI提取文件路径（需要MANAGE_EXTERNAL_STORAGE） */
    private fun getPathFromTreeUri(uri: Uri): String? {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) null
            else {
                val path = docId.substring(colon + 1)
                "/storage/emulated/0/$path"
            }
        } catch (e: Exception) { null }
    }

    private fun doConvert() {
        lastConversionDone = false
        when {
            sourceMode == "single" -> vm.convert(this)
            sourceMode == "folder" -> vm.batchConvertFolder(this)
            else -> vm.batchConvertAll(this)
        }
    }

    private fun refreshButtonStates() {
        // 文件来源：选中用 btn_source_sel，未选用 btn_source
        b.btnSourceSingle.setBackgroundResource(if (sourceMode == "single") R.drawable.btn_source_sel else R.drawable.btn_source)
        b.btnSourceApk.setBackgroundResource(if (sourceMode == "apk") R.drawable.btn_source_sel else R.drawable.btn_source)
        b.btnSourcePick.setBackgroundResource(if (sourceMode == "pick") R.drawable.btn_source_sel else R.drawable.btn_source)
        b.btnSourceFolder.setBackgroundResource(if (sourceMode == "folder") R.drawable.btn_source_sel else R.drawable.btn_source)

        // 保存位置：选中用 btn_cloud_sel，未选用 btn_cloud
        b.btnSaveFolder.setBackgroundResource(if (!saveToGallery) R.drawable.btn_cloud_sel else R.drawable.btn_cloud)
        b.btnSaveGallery.setBackgroundResource(if (saveToGallery) R.drawable.btn_cloud_sel else R.drawable.btn_cloud)
        b.btnSaveCustom.setBackgroundResource(R.drawable.btn_cloud) // 自选按钮无选中态

        // 工具：btnAtlasCut 切换用 btn_tool_sel / btn_tool
        val atlasOn = vm.state.value.atlasCutEnabled
        b.btnAtlasCut.setBackgroundResource(if (atlasOn) R.drawable.btn_tool_sel else R.drawable.btn_tool)

        val canConvert = when (sourceMode) {
            "single" -> !vm.state.value.converting
            "folder" -> !vm.state.value.converting && vm.state.value.inputPath.startsWith("文件夹:")
            else -> !vm.state.value.converting && vm.state.value.apkScanResult != null
        }
        b.btnConvert.isEnabled = canConvert
    }

    private var saveDialogShown = false // 防止重复弹窗

    private fun checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("Android 11+ 需要「所有文件访问」权限才能写入 Download 文件夹。\n\n将跳转到设置页面，请授予权限。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("跳过", null)
                    .show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    b.tvLog.text = state.log
                    b.tvProgress.text = state.batchProgress
                    // 自动滚动日志到底部（通过外层 ScrollView）
                    b.svLog.post {
                        b.svLog.fullScroll(View.FOCUS_DOWN)
                    }
                    if (state.inputPath != "未选择文件") {
                        b.tvFilePath.text = state.inputPath
                        b.llFilePicker.visibility = View.VISIBLE
                    }
                    if (state.apkScanResult != null) {
                        b.tvFilePath.text = "APK: ${state.apkScanResult.atlasKtx.size}图集"
                        b.llFilePicker.visibility = View.VISIBLE
                    }
                    if (state.luaRegionCount >= 0) {
                        b.tvLuaStatus.text = "Lua已加载: ${state.luaRegionCount}区域"
                        b.tvLuaStatus.visibility = View.VISIBLE
                    }
                    b.btnAtlasCut.text = if (state.atlasCutEnabled) "切割: 开" else "切割: 关"
                    b.btnConvert.text = if (state.converting) "转换中..." else "开始转换"
                    // 光遇旋转动画：转换中启动，完成后停止
                    if (state.converting) {
                        b.btnConvert.startAnimation(
                            android.view.animation.AnimationUtils.loadAnimation(
                                this@MainActivity, R.anim.rotate_sky))
                    } else {
                        b.btnConvert.clearAnimation()
                    }
                    // 暂停/继续按钮：根据状态显示不同文字
                    b.btnStop.text = when {
                        state.isPaused -> "继续"
                        state.converting -> "暂停"
                        else -> "暂停"
                    }
                    b.btnStop.isEnabled = state.converting || state.isPaused
                    // 重新开始按钮：运行时启用（橙色），空闲时禁用（灰色）
                    b.btnRestart.isEnabled = state.converting
                    b.btnRestart.backgroundTintList = if (state.converting)
                        ColorStateList.valueOf(Color.parseColor("#FF6F00"))
                    else
                        ColorStateList.valueOf(Color.parseColor("#757575"))

                    // 单张转换完成后弹窗选择保存位置
                    if (state.conversionDone && sourceMode == "single" && !saveDialogShown && !lastConversionDone) {
                        lastConversionDone = true
                        saveDialogShown = true
                        showSaveDialog()
                    }
                    if (!state.conversionDone) {
                        saveDialogShown = false
                    }

                    refreshButtonStates()
                }
            }
        }
    }

    /** 单张转换完成：弹窗选保存方式 */
    private fun showSaveDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle("转换完成")
            .setMessage("选择保存位置：")
            .setPositiveButton("🖼 保存到相册") { _, _ ->
                saveDialogShown = false
                vm.saveToGallery(this)
            }
            .setNeutralButton("📁 选择文件夹...") { _, _ ->
                saveDialogShown = false
                outputDirPicker.launch(null)
            }
            .setNegativeButton("稍后") { _, _ ->
                saveDialogShown = false
            }
            .show()
    }
}