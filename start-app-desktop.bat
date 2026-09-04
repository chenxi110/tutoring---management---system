@echo off
chcp 936 >nul
title 上课通 - 一键启动
cd /d "%~dp0"

echo ============================================================
echo   上课通 - 教学管理系统 一键启动
echo ============================================================
echo.

REM 已启动则直接打开浏览器
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [INFO] 服务已在运行，直接打开网页...
    start "" "http://localhost:8081/tutoring-management.html"
    timeout /t 2 >nul
    exit /b 0
)

echo [START] 正在启动服务（首次启动约需 30-60 秒）...
echo [START] 启动窗口请保持开启，关闭即停止服务
echo.
start "Shangketong Server" cmd /c "%~dp0start-app.bat"

REM 等待端口就绪，最多等 120 秒
set /a cnt=0
:wait
timeout /t 3 >nul
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 goto opened
set /a cnt+=1
if %cnt% lss 40 goto wait

echo.
echo [WARN] 等待超时，服务可能启动失败，请查看服务窗口日志
timeout /t 5 >nul
exit /b 1

:opened
echo [OK] 服务启动成功，正在打开网页...
start "" "http://localhost:8081/tutoring-management.html"
timeout /t 2 >nul
exit /b 0
