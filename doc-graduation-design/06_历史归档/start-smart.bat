@echo off
chcp 936 >nul
title 上课通启动器

echo ============================================================
echo    上课通 - 教学管理系统 智能启动器
echo ============================================================
echo.

cd /d "%~dp0"

REM 检查应用是否已在运行
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [INFO] 应用已在运行中，直接打开浏览器...
    goto open_browser
)

REM 检查Java
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] 未检测到Java环境，请安装JDK 17+
    pause
    exit /b 1
)

REM 检查jar包
if not exist "target\skt-server-1.0.0.jar" (
    echo [ERROR] 未找到jar包，请先执行 mvn package -DskipTests
    pause
    exit /b 1
)

echo [START] 正在启动上课通系统...
echo [START] 首次启动约需10-30秒，请耐心等待...
echo.

REM 后台启动Java应用（不阻塞，窗口隐藏）
start "上课通后台" /min java -Xms128m -Xmx512m -jar target\skt-server-1.0.0.jar

REM 等待应用启动（最多等待60秒）
set /a count=0
:wait_loop
timeout /t 2 >nul
set /a count+=2
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [OK] 应用启动成功！耗时约 %count% 秒
    goto open_browser
)
if %count% geq 60 (
    echo [WARN] 启动超时，请稍后手动刷新浏览器访问
    goto open_browser
)
echo [WAIT] 正在启动... %count% 秒
goto wait_loop

:open_browser
echo.
echo [OPEN] 正在打开浏览器访问: http://localhost:8081/tutoring-management.html
start "" "http://localhost:8081/tutoring-management.html"
echo.
echo [完成] 上课通系统已启动！
echo [提示] 关闭此窗口不会停止应用，应用在后台继续运行
echo [停止] 如需停止应用，请运行 stop-app.bat
timeout /t 3 >nul
exit /b 0