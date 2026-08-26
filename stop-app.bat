@echo off
chcp 936 >nul
title Shangketong - Stop

echo ============================================================
echo    Stop Shangketong Application
echo ============================================================
echo.

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8081" ^| findstr "LISTENING"') do (
    echo [STOP] Killing process PID: %%a
    taskkill /F /PID %%a >nul 2>&1
)

tasklist /fi "imagename eq java.exe" /fo csv 2>nul | findstr "java.exe" >nul
if %errorlevel% equ 0 (
    echo [INFO] Java process still running, stop manually if needed
) else (
    echo [DONE] Application stopped
)

echo.
timeout /t 2 >nul
