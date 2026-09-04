@echo off
cd /d "%~dp0"
if not exist "%~dp0logs" mkdir "%~dp0logs"

REM 等待 MySQL 就绪（最多90秒，防止开机时 MySQL 尚未完全启动）
set /a mc=0
:waitmysql
netstat -ano | findstr ":3306" | findstr "LISTENING" >nul
if %errorlevel% equ 0 goto mysqlok
set /a mc+=1
if %mc% geq 30 exit /b 1
timeout /t 3 >nul
goto waitmysql
:mysqlok

REM 服务已运行则跳过
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 exit /b 0

REM 启动服务（隐藏运行，日志落盘）
java -Xms128m -Xmx512m -jar target\skt-server-1.0.0.jar >> "%~dp0logs\app.log" 2>&1