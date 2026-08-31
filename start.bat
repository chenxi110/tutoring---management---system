@echo off
chcp 65001 >nul 2>&1
title 上课通教学管理系统 - 启动

setlocal enabledelayedexpansion

REM ============================================================
REM 上课通教学管理系统 启动脚本 (Windows)
REM 功能：端口冲突检测 → 启动应用 → 日志输出
REM ============================================================

set APP_NAME=上课通教学管理系统
set JAR_NAME=skt-server-1.0.0.jar
set JAR_PATH=target\%JAR_NAME%
set PORT=8081
set LOG_DIR=logs
set LOG_FILE=%LOG_DIR%\skt-server.log
set PID_FILE=%LOG_DIR%\skt-server.pid

REM 检查 JAR 包是否存在
if not exist "%JAR_PATH%" (
    echo [错误] 未找到 %JAR_PATH%
    echo 请先执行: mvn clean package -DskipTests
    pause
    exit /b 1
)

REM 检查 Java 是否可用
java -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Java 运行环境，请安装 JDK 17+
    pause
    exit /b 1
)

REM 检查端口是否被占用
echo [信息] 检查端口 %PORT% 是否被占用...
netstat -ano | findstr ":%PORT% " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [警告] 端口 %PORT% 已被占用，尝试查找并关闭旧进程...
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
        echo [信息] 关闭旧进程 PID: %%a
        taskkill /F /PID %%a >nul 2>&1
    )
    timeout /t 2 /nobreak >nul
)

REM 创建日志目录
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM 检查是否已在运行
if exist "%PID_FILE%" (
    set /p OLD_PID=<"%PID_FILE%"
    tasklist /FI "PID eq !OLD_PID!" 2>nul | findstr "!OLD_PID!" >nul 2>&1
    if not errorlevel 1 (
        echo [警告] 应用可能已在运行 (PID: !OLD_PID!)，先关闭旧进程...
        taskkill /F /PID !OLD_PID! >nul 2>&1
        timeout /t 2 /nobreak >nul
    )
    del "%PID_FILE%" >nul 2>&1
)

REM JVM 参数：堆内存、GC、编码
set JVM_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8

echo ============================================================
echo   %APP_NAME% 启动中...
echo   端口: %PORT%
echo   日志: %LOG_FILE%
echo   JVM: %JVM_OPTS%
echo ============================================================

REM 后台启动应用，输出重定向到日志文件
start "%APP_NAME%" /B java %JVM_OPTS% -jar "%JAR_PATH%" --spring.profiles.active=local > "%LOG_FILE%" 2>&1

REM 等待启动并获取 PID
timeout /t 3 /nobreak >nul

REM 通过端口查找进程 PID 并写入 pid 文件
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    echo %%a > "%PID_FILE%"
    echo [信息] 应用启动成功，PID: %%a
    goto :started
)

echo [警告] 未能立即确认端口监听，正在检查日志...
timeout /t 5 /nobreak >nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    echo %%a > "%PID_FILE%"
    echo [信息] 应用启动成功，PID: %%a
    goto :started
)

echo [错误] 应用启动失败，请查看日志: %LOG_FILE%
type "%LOG_FILE%" | findstr /i "error exception failed" 2>nul
pause
exit /b 1

:started
echo.
echo [成功] %APP_NAME% 已启动！
echo   访问地址: http://localhost:%PORT%
echo   日志文件: %LOG_FILE%
echo   停止应用: 运行 stop.bat
echo.
echo 按任意键关闭此窗口（应用将继续在后台运行）...
pause >nul
endlocal
