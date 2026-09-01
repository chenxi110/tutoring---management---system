@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   正在停止 AlibabaProtect 服务...
echo ============================================

REM 停止服务
sc stop AlibabaProtect 2>nul

REM 禁用服务自动启动
sc config AlibabaProtect start= disabled 2>nul

REM 等待进程退出
timeout /t 2 /nobreak >nul

REM 如果进程仍在运行，强制结束
taskkill /F /IM AlibabaProtect.exe 2>nul

echo.
echo ============================================
echo   验证结果:
echo ============================================

REM 显示服务状态
sc query AlibabaProtect | findstr "STATE"
sc qc AlibabaProtect | findstr "START_TYPE"

echo.
echo 如果显示 "STOPPED" 和 "DISABLED" 则处理成功。
echo 按任意键退出...
pause >nul
