@echo off

title Tutoring System - One-Click Start

cd /d "%~dp0"

if not exist "%~dp0logs" mkdir "%~dp0logs"

set LOG=%~dp0logs\app.log

echo ============================================================

echo   Tutoring Management System - One-Click Start

echo ============================================================

echo.

REM 1) Wait for MySQL (max 90s)

echo [1/4] Checking MySQL service...

set /a mc=0

:waitmysql

netstat -ano | findstr ":3306" | findstr "LISTENING" >nul

if %errorlevel% equ 0 goto mysqlok

set /a mc+=1

if %mc% geq 30 (

    echo [ERROR] MySQL is not running. Please start MySQL80 service.

    pause

    exit /b 1

)

ping -n 4 127.0.0.1 >nul

goto waitmysql

:mysqlok

echo [OK] MySQL is ready.

REM 2) Already running?

netstat -ano | findstr ":8081" | findstr "LISTENING" >nul

if %errorlevel% equ 0 (

    echo [INFO] Service already running, opening browser...

    start "" "http://localhost:8081/tutoring-management.html"

    ping -n 2 127.0.0.1 >nul

    exit /b 0

)

REM 3) Check jar

if not exist "%~dp0target\skt-server-1.0.0.jar" (

    echo [ERROR] target\skt-server-1.0.0.jar not found.

    pause

    exit /b 1

)

REM 4) Start service

echo [2/4] Starting service (30-60s, keep the window open)...

start "Shangketong Server" "%~dp0start-java.bat"

echo [3/4] Waiting for service...

set /a cnt=0

:wait

ping -n 4 127.0.0.1 >nul

netstat -ano | findstr ":8081" | findstr "LISTENING" >nul

if %errorlevel% equ 0 goto opened

set /a cnt+=1

if %cnt% lss 40 goto wait

echo.

echo [WARN] Startup timeout (120s). Check logs\app.log

notepad "%LOG%"

pause

exit /b 1

:opened

echo [OK] Service started!

echo [4/4] Opening browser...

start "" "http://localhost:8081/tutoring-management.html"

ping -n 2 127.0.0.1 >nul

exit /b 0
