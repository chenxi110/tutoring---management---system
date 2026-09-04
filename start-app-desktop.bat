@echo off
chcp 936 >nul
title 上课通 - 一键启动
cd /d "%~dp0"

if not exist "%~dp0logs" mkdir "%~dp0logs"
set LOG=%~dp0logs\app.log

echo ============================================================
echo   上课通 - 教学管理系统 一键启动
echo ============================================================
echo.

REM 1) 等待 MySQL 就绪（最多90秒，防止开机时 MySQL 尚未完全启动）
echo [1/4] 检查 MySQL 数据库服务...
set /a mc=0
:waitmysql
netstat -ano | findstr ":3306" | findstr "LISTENING" >nul
if %errorlevel% equ 0 goto mysqlok
set /a mc+=1
if %mc% geq 30 (
    echo [ERROR] MySQL 数据库未启动，请先启动 MySQL 服务
    echo         （服务名 MySQL80，可在 服务 中手动启动）
    pause
    exit /b 1
)
timeout /t 3 >nul
goto waitmysql
:mysqlok
echo [OK] MySQL 数据库已就绪

REM 2) 服务已在运行则直接打开网页
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [INFO] 服务已在运行，直接打开网页...
    start "" "http://localhost:8081/tutoring-management.html"
    timeout /t 2 >nul
    exit /b 0
)

REM 3) 检查程序文件
if not exist "%~dp0target\skt-server-1.0.0.jar" (
    echo [ERROR] 程序文件缺失: target\skt-server-1.0.0.jar
    echo         请重新执行 mvn package -DskipTests 打包
    pause
    exit /b 1
)

REM 4) 启动服务（独立窗口，保持开启即服务运行；日志写入 logs\app.log）
echo [2/4] 正在启动服务（首次约需30-60秒，请勿关闭服务窗口）...
echo [%date% %time%] ===== 上课通 手动启动 ===== >> "%LOG%"
start "Shangketong Server" cmd /c "cd /d %~dp0 && java -Xms128m -Xmx512m -jar target\skt-server-1.0.0.jar >> \"%LOG%\" 2>&1"

REM 等待端口就绪，最多120秒
echo [3/4] 等待服务就绪...
set /a cnt=0
:wait
timeout /t 3 >nul
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 goto opened
set /a cnt+=1
if %cnt% lss 40 goto wait

echo.
echo [WARN] 服务启动超时（超过120秒），可能启动失败
echo        请查看日志文件: logs\app.log
notepad "%LOG%"
pause
exit /b 1

:opened
echo [OK] 服务启动成功！
echo [4/4] 正在打开网页...
start "" "http://localhost:8081/tutoring-management.html"
timeout /t 2 >nul
exit /b 0