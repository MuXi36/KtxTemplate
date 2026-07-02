# KtxTemplate — Sky: CotL KTX Texture to PNG Converter

[![中文](README.md)](README.md) [![EN](https://img.shields.io/badge/lang-EN-blue)]()

Android batch converter for _Sky: Children of the Light_ KTX texture files.

Supports ETC2 / EAC / ASTC compressed texture decoding, automatic Atlas texture segmentation, color restoration via a built-in 182-step warm tone palette to faithfully reproduce Sky's original artwork, and final export as standard PNG format.

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🎮 KTX Parsing | Full KTX1 header parsing; 14 compressed formats including R11_EAC (0x9270), RGBA8_ETC2_EAC (0x9278), ASTC 4×4 (0x93B0), ASTC 6×6 (0x93D0) |
| 🔄 EAC Decoding | PVRTexTool v5.7 CLI decodes Imagination EAC textures to RGBA raw data |
| 🗜️ ASTC Decoding | ARM astcenc native binary decodes ASTC textures |
| 🎨 Color Restoration | Built-in 182-step warm tone palette (`色彩调色板.json`) faithfully reproduces Sky's original color grading |
| ✂️ Atlas Cutting | Automatic boundary detection — cuts atlas into individual PNGs without any config file |
| 🖼️ Post-processing | Contrast enhancement, sharpening, Gaussian denoising for export quality |
| 📱 Sky-themed UI | Aurora gradient background + 24 rotating multi-color beams + procedural stardust particles + pulsing breath animations |
| 🔓 APK Source Mode | Extract textures directly from installed game APK via Shizuku — no root needed |

## 🚀 Build

**Requirements:** JDK 17+

```bash
git clone https://github.com/MuXi36/KtxTemplate.git
cd KtxTemplate
bash build.sh          # Debug
bash build.sh release  # Release
```

The script auto-detects `$ANDROID_HOME` (`~/Android/Sdk`, `/opt/android-sdk`, etc.) — no need to manually edit `local.properties`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### CI / CD

Every push triggers [GitHub Actions](.github/workflows/build.yml): builds a Debug APK and uploads as an Artifact (retained 90 days).

## 📁 Project Structure

```
KtxTemplate/
├── build.sh                         # One-click build script
├── build.gradle                     # Root config (AGP 8.2.2 + Kotlin 1.9.20)
├── settings.gradle                  # Project settings
├── gradlew / gradlew.bat            # Gradle Wrapper
├── app/
│   ├── build.gradle                 # App build config
│   ├── proguard-rules.pro           # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml      # Manifest (storage + Shizuku)
│       ├── java/com/ktxconverter/app/
│       │   ├── MainActivity.kt          # Main UI + file picker + mode toggle
│       │   ├── ConverterViewModel.kt    # Conversion pipeline orchestrator
│       │   ├── KtxParser.kt             # KTX1 header parser
│       │   ├── PvrKtxConverter.kt       # PVRTexTool EAC decode pipeline
│       │   ├── EtcDecoder.kt            # ETC software decoder (fallback)
│       │   ├── EtcEncoder.kt            # ETC software encoder (debug)
│       │   ├── AtlasCutter.kt           # Atlas auto-cut algorithm
│       │   ├── ImageEnhancer.kt         # Post-processing (contrast/sharpen/denoise)
│       │   ├── ApkSourceManager.kt      # Shizuku APK source manager
│       │   ├── UiColorizer.kt           # Dynamic UI color engine
│       │   ├── AuroraBackground.kt      # Aurora gradient (Canvas draw)
│       │   ├── BeamsView.kt             # 24 multi-color rotating beams
│       │   ├── StardustOverlay.kt       # Procedural stardust system
│       │   ├── PulseAnimator.kt         # Pulsing breath animation
│       │   ├── ShinyTextView.kt         # Shimmer gradient text
│       │   ├── CandleFlameView.kt       # Candle flame animation View
│       │   ├── GlareSweepEffect.kt      # Glare sweep overlay
│       │   └── GrainientOverlay.kt      # Film grain texture overlay
│       ├── res/
│       │   ├── layout/activity_main.xml # Main layout
│       │   ├── drawable/                # Backgrounds / buttons / icons / dividers
│       │   ├── values/                  # Colors / strings / themes (day+night)
│       │   └── anim/                    # Animation resources
│       └── assets/
│           ├── UIPackedAtlas.lua        # UI texture atlas
│           └── linux/                   # Embedded Linux aarch64 runtime
│               ├── bin/
│               │   ├── python3              # Python 3.12 interpreter
│               │   ├── pvr_cli              # PVRTexTool v5.7 CLI
│               │   ├── astcenc              # ASTC native decoder
│               │   ├── ktx2png.py           # Main KTX→PNG converter
│               │   ├── ktx2png_raw.py       # Raw data export
│               │   ├── ktx2png_color.py     # Color-mapped export
│               │   ├── ktx2png_astc.py      # ASTC format export
│               │   ├── atlas_cutter.py      # Atlas cutting script
│               │   └── 色彩调色板.json       # 182-step warm tone palette
│               └── lib/                     # Runtime shared libs
│                   ├── libpython3.12.so.1.0
│                   ├── libc.so.6 / libm.so.6 / libstdc++.so.6
│                   └── libexpat.so.1 / libz.so.1
```

## 🛠️ Tech Stack

### Android

| Technology | Role |
|------------|------|
| Kotlin 1.9.20 + Android View | Primary language & UI (no Jetpack Compose) |
| Android Gradle Plugin 8.2.2 | Build system |
| AndroidX Core KTX 1.12 | Kotlin extensions |
| AndroidX AppCompat 1.6 | Backward compatibility |
| Material Design 1.11 | Material components |
| ConstraintLayout 2.1 | Constraint-based layout |
| Lifecycle + ViewModel KTX 2.7 | MVVM architecture |
| Activity + Fragment KTX | Activity/Fragment extensions |
| DocumentFile 1.0 | SAF file access |
| Kotlin Coroutines 1.7.3 | Async conversion pipeline |
| ViewBinding | View binding |
| Custom Canvas drawing | Aurora / beams / stardust / flame effects (zero third-party animation libs) |

### Embedded Linux Runtime

| Component | Source | Role |
|-----------|--------|------|
| Python 3.12 | python.org | KTX→PNG script runtime |
| NumPy | numpy.org | Pixel array ops & color mapping |
| PVRTexTool CLI v5.7 | Imagination Technologies | EAC/ETC texture decoding |
| astcenc v4.x | ARM Ltd. | ASTC texture decoding |

### External Integrations

| Technology | Role |
|------------|------|
| Shizuku | Privileged access to installed APK resources (APK Source mode) |
| GitHub Actions | CI auto-build |

## 🙏 Acknowledgments

This project builds on the following open-source projects and tools:

- **[Imagination Technologies](https://www.imaginationtech.com/)** — PVRTexTool, the industry-standard PowerVR texture toolchain. This project uses its CLI for EAC decoding.
- **[ARM Ltd.](https://www.arm.com/)** — astcenc, the reference ASTC encoder/decoder. This project uses its aarch64 native binary for ASTC decoding.
- **[JetBrains](https://www.jetbrains.com/kotlin/)** — Kotlin language & IntelliJ IDEA
- **[Google Android](https://developer.android.com/)** — Android SDK, AndroidX ecosystem, Material Design
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** — Root-free privilege management by RikkaApps
- **[thatgamecompany](https://thatgamecompany.com/)** — _Sky: Children of the Light_, a heartfelt artistic game. This project was created to study its KTX texture format.
- **[rgl/hello-world-kotlin-android](https://github.com/rgl/hello-world-kotlin-android)** — Project starter template

## 📄 License

MIT License

---

> ⚠️ This project is for educational research of the KTX texture format and Android native compilation techniques. Please respect the game's copyright — do not use for commercial purposes.
>
> 📝 Docs / CI scaffolding assisted by Claude (Ponytail).