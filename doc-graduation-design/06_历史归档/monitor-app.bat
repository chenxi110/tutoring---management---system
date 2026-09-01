@echo off
chcp 936 >nul
title 上课通系统监控
set "APP_DIR=%~dp0"
set "LOG_FILE=%APP_DIR%monitor.log"
set "PORT=8081"
set "JAR_FILE=%APP_DIR%target\skt-server-1.0.0.jar"

echo [%date% %time%] 监控脚本启动，每30秒检查一次应用状态 >> "%LOG_FILE%"

:loop
timeout /t 30 >nul
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    REM 应用正常运行，不做任何操作
    goto loop
)
echo [%date% %time%] [WARN] 检测到应用未运行，正在自动重启... >> "%LOG_FILE%"
REM 杀掉可能残留的Java进程
taskkill /F /IM java.exe & taskkill /F /IM javaw.exe >nul 2>&1
timeout /t 3 >nul
REM 启动应用
cd /d "%APP_DIR%"
start "上课通后台" /min javaw -Xms128m -Xmx256m -jar "%JAR_FILE%"
echo [%date% %time%] [OK] 应用已自动重启 >> "%LOG_FILE%"
goto loop