@echo off
cd /d "%~dp0"
if not exist "%~dp0logs" mkdir "%~dp0logs"

REM Wait for MySQL (max 90s)
set /a mc=0

:waitmysql
netstat -ano | findstr ":3306" | findstr "LISTENING" >nul
if %errorlevel% equ 0 goto mysqlok
set /a mc+=1
if %mc% geq 30 exit /b 1
ping -n 4 127.0.0.1 >nul
goto waitmysql

:mysqlok
REM Already running?
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 exit /b 0

REM Start service (hidden, log to file)
cd /d "%~dp0"
java -Xms128m -Xmx512m -jar target\skt-server-1.0.0.jar >> "%~dp0logs\app.log" 2>&1
