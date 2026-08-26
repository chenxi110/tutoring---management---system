@echo off
chcp 65001 >nul
echo ====================================
echo    上课通系统 v2.0.0 - 启动脚本
echo ====================================
echo.

:: 检查Node.js是否安装
where node >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未检测到Node.js，请先安装Node.js
    echo 下载地址: https://nodejs.org/
    pause
    exit /b 1
)

echo [√] Node.js已安装
node --version

:: 检查依赖是否安装
if not exist "node_modules" (
    echo.
    echo [信息] 正在安装项目依赖...
    call npm install
    if %errorlevel% neq 0 (
        echo [错误] 依赖安装失败
        pause
        exit /b 1
    )
    echo [√] 依赖安装完成
)

echo.
echo [信息] 正在启动上课通系统...
echo [信息] 请在浏览器中访问: http://localhost:3001
echo.
echo [提示] 按 Ctrl+C 可停止服务
echo ====================================
echo.

:: 启动服务
node ai-service.js

pause