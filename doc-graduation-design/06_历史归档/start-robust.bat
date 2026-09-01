@echo off
chcp 936 >nul
setlocal enabledelayedexpansion

title 上课通系统启动器

set "APP_DIR=%~dp0"
set "LOG_FILE=%APP_DIR%startup.log"
set "JAR_FILE=%APP_DIR%target\skt-server-1.0.0.jar"
set "PORT=8081"
set "MAX_RETRY=5"
set "RETRY_COUNT=0"

echo ============================================================ >> "%LOG_FILE%"
echo [%date% %time%] 上课通系统启动器开始运行 >> "%LOG_FILE%"
echo ============================================================ >> "%LOG_FILE%"

cd /d "%APP_DIR%"

REM 检查是否已在运行
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [%date% %time%] 应用已在运行中，端口 %PORT% 已监听 >> "%LOG_FILE%"
    echo [INFO] 应用已在运行中
    goto open_browser
)

REM 检查Java环境
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [%date% %time%] [ERROR] 未检测到Java环境 >> "%LOG_FILE%"
    echo [ERROR] 未检测到Java环境，请安装JDK 17+
    pause
    exit /b 1
)

REM 检查jar包
if not exist "%JAR_FILE%" (
    echo [%date% %time%] [ERROR] 未找到jar包: %JAR_FILE% >> "%LOG_FILE%"
    echo [ERROR] 未找到jar包，请先执行 mvn package -DskipTests
    pause
    exit /b 1
)

REM 等待MySQL就绪（最多等待30秒）
echo [%date% %time%] 等待MySQL服务就绪... >> "%LOG_FILE%"
set /a MYSQL_WAIT=0
:wait_mysql
netstat -ano | findstr ":3306" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [%date% %time%] MySQL已就绪 >> "%LOG_FILE%"
    goto start_app
)
set /a MYSQL_WAIT+=2
if %MYSQL_WAIT% geq 30 (
    echo [%date% %time%] [WARN] MySQL等待超时，继续启动... >> "%LOG_FILE%"
    goto start_app
)
timeout /t 2 >nul
goto wait_mysql

:start_app
echo [%date% %time%] 启动Java应用... >> "%LOG_FILE%"
echo [START] 正在启动上课通系统...
echo [START] 首次启动约需10-30秒

REM 后台启动Java应用
start "上课通后台" /min javaw -Xms128m -Xmx256m -jar "%JAR_FILE%"

REM 等待应用启动并检测端口（最多等待90秒，每5秒检测一次）
set /a WAIT_COUNT=0
:wait_port
timeout /t 5 >nul
set /a WAIT_COUNT+=5
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [%date% %time%] [OK] 应用启动成功！端口 %PORT% 已监听，耗时约 %WAIT_COUNT% 秒 >> "%LOG_FILE%"
    echo [OK] 应用启动成功！耗时约 %WAIT_COUNT% 秒
    goto open_browser
)
if %WAIT_COUNT% geq 90 (
    echo [%date% %time%] [ERROR] 应用启动超时（90秒），端口未监听 >> "%LOG_FILE%"
    echo [ERROR] 启动超时，请查看日志文件: %LOG_FILE%
    goto retry
)
echo [WAIT] 正在启动... %WAIT_COUNT% 秒
goto wait_port

:retry
set /a RETRY_COUNT+=1
if %RETRY_COUNT% geq %MAX_RETRY% (
    echo [%date% %time%] [ERROR] 达到最大重试次数 %MAX_RETRY%，启动失败 >> "%LOG_FILE%"
    echo [ERROR] 启动失败，请查看日志: %LOG_FILE%
    pause
    exit /b 1
)
echo [%date% %time%] [RETRY] 第 %RETRY_COUNT% 次重试... >> "%LOG_FILE%"
echo [RETRY] 第 %RETRY_COUNT% 次重试...
REM 杀掉可能存在的Java进程
taskkill /F /IM java.exe >nul 2>&1
timeout /t 3 >nul
goto start_app

:open_browser
echo [%date% %time%] 打开浏览器访问应用 >> "%LOG_FILE%"
echo.
echo [OPEN] 正在打开浏览器: http://localhost:%PORT%/tutoring-management.html
start "" "http://localhost:%PORT%/tutoring-management.html"
echo.
echo [完成] 上课通系统已启动！
echo [提示] 关闭此窗口不会停止应用
echo [停止] 运行 stop-app.bat 停止应用
echo [%date% %time%] 启动器执行完成 >> "%LOG_FILE%"
timeout /t 3 >nul
exit /b 0