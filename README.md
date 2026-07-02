# KtxTemplate — 光遇 KTX 纹理转 PNG 工具

Android 端光遇（Sky: Children of the Light）KTX 纹理文件批量转换器，支持 EAC/ASTC 格式解码并导出为 PNG。

## 功能

- 🎮 支持 R11_EAC (0x9270)、ASTC 4x4 (0x93B0)、ASTC 6x6 (0x93D0) 等 KTX 格式
- 🎨 内置 182 阶暖色调色板，还原光遇原画色彩
- ✂️ Atlas 纹理自动切割为独立 PNG（无需配置文件）
- 🖼️ 图片后处理：对比度增强、锐化、去噪
- ✨ 光遇风格 UI：极光背景、24 条多色旋转射线、星尘粒子

## 编译

**环境要求：** JDK 17+，Android SDK 自动检测

```bash
git clone https://github.com/MuXi36/KtxTemplate.git
cd KtxTemplate
bash build.sh
```

脚本自动检测 `$ANDROID_HOME` 常见路径，无需手动配置。

也支持 release 模式：`bash build.sh release`

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
├── app/src/main/java/com/ktxconverter/app/
│   ├── MainActivity.kt          # 主界面
│   ├── ConverterViewModel.kt    # 转换核心逻辑
│   ├── KtxParser.kt             # KTX 文件头解析
│   ├── PvrKtxConverter.kt       # PVRTexTool EAC 解码管线
│   ├── EtcDecoder.kt            # ETC 软解码
│   ├── AtlasCutter.kt           # Atlas 纹理切割
│   ├── ImageEnhancer.kt         # 图片后处理
│   ├── BeamsView.kt             # 24 条旋转射线特效
│   ├── AuroraBackground.kt      # 极光背景
│   ├── StardustOverlay.kt       # 星尘粒子
│   └── ...
├── app/src/main/res/layout/
│   └── activity_main.xml        # 主布局（NestedScrollView + 日志区）
├── app/src/main/assets/linux/   # 嵌入运行环境
│   ├── bin/pvr_cli              # PVRTexTool CLI
│   ├── bin/astcenc              # ASTC 原生解码器
│   ├── bin/python3              # Python 3.12 运行时
│   └── bin/ktx2png*.py          # 转换脚本
├── build.sh                     # 一键编译脚本
└── gradle/                      # Gradle Wrapper
```

## 技术栈

- Kotlin + Android View (XML layout)
- PVRTexTool v5.7 EAC 解码
- astcenc 原生二进制 ASTC 解码
- 自定义 Canvas 绘制光遇风格动效（无第三方动画库）
- Shizuku 支持（apk_source 模式）

## License

MIT