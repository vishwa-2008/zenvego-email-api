@echo off
chcp 65001 >nul
echo ============================================================
echo   ZENVEGO BACKEND LAUNCHER
echo ============================================================
echo.
cd /d "%~dp0"
REM Bypass any PowerShell ExecutionPolicy and run the full .ps1 script.
REM The .ps1 handles: env load, validation, zombie-kill, maven build, run java
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dpn0.ps1"
pause
