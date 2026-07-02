#!/bin/bash
# KtxTemplate 一键编译脚本
# 用法: bash build.sh [debug|release]
set -e

MODE="${1:-debug}"

# 自动找 Android SDK
if [ -z "$ANDROID_HOME" ]; then
    # 常见路径
    for p in "$HOME/Android/Sdk" "/opt/android-sdk" "$HOME/android-sdk"; do
        [ -d "$p/platforms" ] && ANDROID_HOME="$p" && break
    done
fi

if [ -z "$ANDROID_HOME" ]; then
    echo "❌ 未找到 Android SDK，请设置 ANDROID_HOME 或创建 local.properties"
    echo "   export ANDROID_HOME=/path/to/Android/Sdk"
    exit 1
fi

# 写入 local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo ">> SDK: $ANDROID_HOME"
echo ">> 编译模式: $MODE"

case "$MODE" in
    debug)
        ./gradlew assembleDebug
        APK="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        # 尝试用已有签名，没有则生成 debug 签名版
        if [ -f "keystore.jks" ]; then
            ./gradlew assembleRelease
            APK="app/build/outputs/apk/release/app-release.apk"
        else
            echo "!! 未找到 keystore.jks，生成 debug 签名 release"
            ./gradlew assembleRelease -Pandroid.injected.signing.store.file=/dev/null
            APK="app/build/outputs/apk/release/app-release-unsigned.apk"
        fi
        ;;
    *)
        echo "未知模式: $MODE，用 debug"
        ./gradlew assembleDebug
        APK="app/build/outputs/apk/debug/app-debug.apk"
        ;;
esac

if [ -f "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo "✅ 编译完成: $APK ($SIZE)"
else
    echo "❌ 编译失败"
    exit 1
fi