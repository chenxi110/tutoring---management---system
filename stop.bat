@echo off
chcp 65001 >nul 2>&1
title 上课通教学管理系统 - 停止

setlocal enabledelayedexpansion

REM ============================================================
REM 上课通教学管理系统 停止脚本 (Windows)
REM 功能：读取 PID 文件 → 优雅关闭 → 端口确认 → 强制兜底
REM ============================================================

set APP_NAME=上课通教学管理系统
set PORT=8081
set LOG_DIR=logs
set PID_FILE=%LOG_DIR%\skt-server.pid

echo [信息] 正在停止 %APP_NAME%...

REM 优先通过 PID 文件停止
if exist "%PID_FILE%" (
    set /p APP_PID=<"%PID_FILE%"
    if defined APP_PID (
        echo [信息] 找到 PID 文件，进程 PID: !APP_PID!
        tasklist /FI "PID eq !APP_PID!" 2>nul | findstr "!APP_PID!" >nul 2>&1
        if not errorlevel 1 (
            echo [信息] 发送关闭信号到进程 !APP_PID!...
            taskkill /PID !APP_PID! >nul 2>&1
            timeout /t 5 /nobreak >nul
            REM 检查是否已关闭
            tasklist /FI "PID eq !APP_PID!" 2>nul | findstr "!APP_PID!" >nul 2>&1
            if errorlevel 1 (
                echo [成功] 进程 !APP_PID! 已优雅关闭
                del "%PID_FILE%" >nul 2>&1
                goto :check_port
            ) else (
                echo [警告] 优雅关闭超时，强制终止进程 !APP_PID!...
                taskkill /F /PID !APP_PID! >nul 2>&1
                timeout /t 2 /nobreak >nul
                del "%PID_FILE%" >nul 2>&1
                goto :check_port
            )
        ) else (
            echo [信息] PID !APP_PID! 对应的进程不存在，清理 PID 文件
            del "%PID_FILE%" >nul 2>&1
        )
    )
)

:check_port
REM 兜底：通过端口查找并关闭
echo [信息] 检查端口 %PORT% 是否仍有进程监听...
netstat -ano | findstr ":%PORT% " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [警告] 端口 %PORT% 仍被占用，强制关闭...
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
        echo [信息] 强制终止 PID: %%a
        taskkill /F /PID %%a >nul 2>&1
    )
    timeout /t 2 /nobreak >nul
)

REM 最终确认
netstat -ano | findstr ":%PORT% " | findstr "LISTENING" >nul 2>&1
if errorlevel 1 (
    echo [成功] %APP_NAME% 已停止，端口 %PORT% 已释放
) else (
    echo [错误] 无法停止端口 %PORT% 上的进程，请手动检查
    netstat -ano | findstr ":%PORT% " | findstr "LISTENING"
)

echo.
echo 按任意键退出...
pause >nul
endlocal
