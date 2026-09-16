@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set LWJGL_VERSION=3.3.6
set JOML_VERSION=1.10.5
set JLAYER_VERSION=1.0.1.4

REM Build classes or download the current native libraries when missing.
if not exist "out\com\mineclone\Main.class" (
    echo First-time setup, compiling...
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
    exit /b %errorlevel%
)
if not exist "libs\lwjgl-%LWJGL_VERSION%-natives-windows.jar" (
    echo First-time setup, compiling...
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
    exit /b %errorlevel%
)

REM Keep the classpath version-pinned. Old downloadable jars may remain in
REM libs after an update; a wildcard would load whichever version came first.
set CP=out
for %%m in (lwjgl lwjgl-glfw lwjgl-opengl lwjgl-stb lwjgl-openal) do (
    set CP=!CP!;libs\%%m-%LWJGL_VERSION%.jar
    set CP=!CP!;libs\%%m-%LWJGL_VERSION%-natives-windows.jar
)
set CP=!CP!;libs\joml-%JOML_VERSION%.jar
set CP=!CP!;libs\jlayer-%JLAYER_VERSION%.jar

java -cp "!CP!" com.mineclone.Main %*
if errorlevel 1 (
    echo.
    echo Game exited with error %errorlevel%.
    pause
)
