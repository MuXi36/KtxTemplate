# KtxTemplate — 光遇 KTX 纹理转 PNG 工具

[![中文](https://img.shields.io/badge/lang-中文-red)]() [![EN](https://img.shields.io/badge/lang-EN-blue)](README_EN.md)

Android 端《光·遇》（Sky: Children of the Light）KTX 纹理文件批量转换器。

支持 ETC2 / EAC / ASTC 等压缩纹理格式解码，自动切割 Atlas 纹理集，通过内置 182 阶暖色调色板还原光遇原画色彩，最终导出为 PNG 标准格式。

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🎮 KTX 解析 | 完整 KTX1 文件头解析，支持 R11_EAC (0x9270)、RGBA8_ETC2_EAC (0x9278)、ASTC 4×4 (0x93B0)、ASTC 6×6 (0x93D0) 等 14 种压缩格式 |
| 🔄 EAC 解码 | 调用 PVRTexTool v5.7 CLI 解码 Imagination EAC 纹理为 RGBA 原始数据 |
| 🗜️ ASTC 解码 | 使用 ARM astcenc 原生二进制解码 ASTC 纹理 |
| 🎨 色彩还原 | 内置 182 阶暖色调色板（`色彩调色板.json`），模拟光遇原画调色 |
| ✂️ Atlas 切割 | 自动检测纹理集边界，将 Atlas 切割为独立 PNG，无需配置文件 |
| 🖼️ 后处理 | 对比度增强、锐化、高斯去噪，提升导出图像质量 |
| 📱 光遇风格 UI | 极光渐变背景 + 24 条多色旋转射线 + 随机星尘粒子 + 脉冲呼吸动画 |
| 🔓 APK 源模式 | 通过 Shizuku 直接读取已安装游戏 APK 提取纹理，无需 root |

## 🚀 编译

**环境要求：** JDK 17+

```bash
git clone https://github.com/MuXi36/KtxTemplate.git
cd KtxTemplate
bash build.sh          # Debug 版
bash build.sh release  # Release 版
```

脚本自动检测 `$ANDROID_HOME`（`~/Android/Sdk`、`/opt/android-sdk` 等常见路径），无需手动配置 `local.properties`。

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 📁 项目结构

```
KtxTemplate/
├── build.sh                         # 一键编译脚本
├── build.gradle                     # 根构建配置（AGP 8.2.2 + Kotlin 1.9.20）
├── settings.gradle                  # 项目设置
├── gradlew / gradlew.bat            # Gradle Wrapper
├── app/
│   ├── build.gradle                 # 应用构建配置
│   ├── proguard-rules.pro           # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml      # 清单（存储权限 + Shizuku）
│       ├── java/com/ktxconverter/app/
│       │   ├── MainActivity.kt          # 主界面 + 文件选择 + 模式切换
│       │   ├── ConverterViewModel.kt    # 转换核心管线编排
│       │   ├── KtxParser.kt             # KTX1 文件头解析器
│       │   ├── PvrKtxConverter.kt       # PVRTexTool EAC 解码管线
│       │   ├── EtcDecoder.kt            # ETC 软解码（备选方案）
│       │   ├── EtcEncoder.kt            # ETC 软编码（调试用）
│       │   ├── AtlasCutter.kt           # Atlas 纹理自动切割算法
│       │   ├── ImageEnhancer.kt         # 图片后处理（对比度/锐化/去噪）
│       │   ├── ApkSourceManager.kt      # Shizuku APK 源管理
│       │   ├── UiColorizer.kt           # UI 动态着色引擎
│       │   ├── AuroraBackground.kt      # 极光渐变背景（Canvas 绘制）
│       │   ├── BeamsView.kt             # 24 条多色旋转射线特效
│       │   ├── StardustOverlay.kt       # 星尘粒子系统（随机生成+动画）
│       │   ├── PulseAnimator.kt         # 脉冲呼吸动画引擎
│       │   ├── ShinyTextView.kt         # 渐变闪烁文本控件
│       │   ├── CandleFlameView.kt       # 蜡烛火焰动画 View
│       │   ├── GlareSweepEffect.kt      # 强光扫过特效
│       │   └── GrainientOverlay.kt      # 噪点纹理叠加层
│       ├── res/
│       │   ├── layout/activity_main.xml # 主布局
│       │   ├── drawable/                # 背景/按钮/图标/分割线
│       │   ├── values/                  # 颜色/字符串/主题（日间+夜间）
│       │   └── anim/                    # 动画资源
│       └── assets/
│           ├── UIPackedAtlas.lua        # UI 纹理集
│           └── linux/                   # 嵌入式 Linux aarch64 运行时
│               ├── bin/
│               │   ├── python3              # Python 3.12 解释器
│               │   ├── pvr_cli              # PVRTexTool v5.7 CLI
│               │   ├── astcenc              # ASTC 原生解码器
│               │   ├── ktx2png.py           # KTX→PNG 转换主脚本
│               │   ├── ktx2png_raw.py       # 原始数据导出
│               │   ├── ktx2png_color.py     # 色彩映射导出
│               │   ├── ktx2png_astc.py      # ASTC 格式导出
│               │   ├── atlas_cutter.py      # Atlas 切割脚本
│               │   └── 色彩调色板.json       # 182 阶暖色调色板
│               └── lib/                     # 运行时动态库
│                   ├── libpython3.12.so.1.0
│                   ├── libc.so.6 / libm.so.6 / libstdc++.so.6
│                   └── libexpat.so.1 / libz.so.1
```

## 🛠️ 技术栈

### Android 端

| 技术 | 用途 |
|------|------|
| Kotlin 1.9.20 + Android View | 主语言与 UI 框架（无 Jetpack Compose） |
| Android Gradle Plugin 8.2.2 | 构建系统 |
| AndroidX Core KTX 1.12 | Kotlin 扩展 |
| AndroidX AppCompat 1.6 | 兼容性支持 |
| Material Design 1.11 | Material 组件 |
| ConstraintLayout 2.1 | 约束布局 |
| Lifecycle + ViewModel KTX 2.7 | MVVM 架构 |
| Activity + Fragment KTX | Activity/Fragment 扩展 |
| DocumentFile 1.0 | SAF 文件访问 |
| Kotlin Coroutines 1.7.3 | 异步转换管线 |
| ViewBinding | 视图绑定 |
| Canvas 自定义绘制 | 极光/射线/星尘/火焰等特效（零第三方动画库） |

### Linux 嵌入式运行时

| 组件 | 来源 | 用途 |
|------|------|------|
| Python 3.12 | python.org | KTX→PNG 转换脚本运行环境 |
| NumPy | numpy.org | 像素数组处理与色彩映射 |
| PVRTexTool CLI v5.7 | Imagination Technologies | EAC/ETC 压缩纹理解码 |
| astcenc v4.x | ARM Ltd. | ASTC 压缩纹理解码 |

### 外部集成

| 技术 | 用途 |
|------|------|
| Shizuku | 提权访问已安装 APK 资源（APK 源模式） |

## 🙏 致谢

本项目基于以下开源项目与工具构建，特此感谢：

- **[Imagination Technologies](https://www.imaginationtech.com/)** — PVRTexTool，业界标准的 PowerVR 纹理处理工具，本项目使用其 CLI 完成 EAC 格式解码
- **[ARM Ltd.](https://www.arm.com/)** — astcenc，ASTC 纹理压缩/解压缩参考实现，本项目使用其 aarch64 原生二进制完成 ASTC 解码
- **[JetBrains](https://www.jetbrains.com/kotlin/)** — Kotlin 语言与 IntelliJ IDEA
- **[Google Android](https://developer.android.com/)** — Android SDK、AndroidX 生态、Material Design
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** — RikkaApps 开发的无需 root 的权限管理方案
- **[thatgamecompany](https://thatgamecompany.com/)** — 《光·遇》（Sky: Children of the Light），一款触动人心的艺术级游戏，本项目为研究其 KTX 纹理格式而创建
- **[rgl/hello-world-kotlin-android](https://github.com/rgl/hello-world-kotlin-android)** — 项目初始模板

## 📄 License

MIT License

---

> ⚠️ 本项目仅供学习研究 KTX 纹理格式与 Android 原生编译技术。请尊重游戏版权，勿用于商业用途。