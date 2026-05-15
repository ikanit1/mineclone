@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Build classes if missing (first run only).
if not exist "out\com\mineclone\Main.class" (
    echo First-time setup, compiling...
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
    exit /b %errorlevel%
)

REM Build classpath from libs\*.jar
set CP=out
for %%j in (libs\*.jar) do set CP=!CP!;%%j

java -cp "!CP!" com.mineclone.Main %*
if errorlevel 1 (
    echo.
    echo Game exited with error %errorlevel%.
    pause
)
