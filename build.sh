#!/usr/bin/env bash
# 一键构建 APK:./build.sh [debug|release],默认 release
set -e
cd "$(dirname "$0")"

# 本机没有全局 JDK,构建必须显式指定(见 README「构建与打包」)
export JAVA_HOME="D:/android-dev/jdk17-extract/jdk-17.0.20.1+1"
export GRADLE_USER_HOME="D:/android-dev/gradle-home"

TYPE="${1:-release}"
case "$TYPE" in
    debug)   TASK=assembleDebug   APK=app/build/outputs/apk/debug/app-debug.apk ;;
    release) TASK=assembleRelease APK=app/build/outputs/apk/release/app-release.apk ;;
    *) echo "用法: ./build.sh [debug|release](默认 release)"; exit 1 ;;
esac

./gradlew "$TASK"

echo
echo "构建完成: $APK"

# release 且已有签名配置时,用 apksigner 验一下(jarsigner 对 v2 签名会误报「未签名」)
if [ "$TYPE" = release ] && [ -f keystore.properties ]; then
    "D:/android-dev/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs "$APK"
fi
