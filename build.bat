@echo off
rem 一键构建 APK:build.bat [debug|release],默认 release(可直接双击运行)
setlocal
cd /d "%~dp0"

rem 本机没有全局 JDK,构建必须显式指定(见 README「构建与打包」)
set "JAVA_HOME=D:\android-dev\jdk17-extract\jdk-17.0.20.1+1"
set "GRADLE_USER_HOME=D:\android-dev\gradle-home"

set "TYPE=%~1"
if "%TYPE%"=="" set "TYPE=release"
set "TASK=assembleRelease"
set "APK=app\build\outputs\apk\release\app-release.apk"
if /i "%TYPE%"=="debug" set "TASK=assembleDebug"
if /i "%TYPE%"=="debug" set "APK=app\build\outputs\apk\debug\app-debug.apk"
if /i "%TYPE%"=="release" goto :build
if /i "%TYPE%"=="debug" goto :build
echo 用法: build.bat [debug^|release](默认 release)
exit /b 1

:build
call gradlew.bat %TASK%

echo.
echo 构建完成: %APK%

rem release 且已有签名配置时,用 apksigner 验一下(jarsigner 对 v2 签名会误报「未签名」)
if /i "%TYPE%"=="release" if exist keystore.properties (
    call "D:\android-dev\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs "%APK%"
)

pause
