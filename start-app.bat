@echo off
chcp 936 >nul
title Shangketong - Starting...

echo ============================================================
echo    Shangketong Tutoring Management System
echo ============================================================
echo.

cd /d "%~dp0"

REM Check Java
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found, please install JDK 17+
    pause
    exit /b 1
)

REM Check jar
if not exist "target\skt-server-1.0.0.jar" (
    echo [ERROR] target\skt-server-1.0.0.jar not found
    echo Please run: mvn package -DskipTests
    pause
    exit /b 1
)

REM Check port
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel% equ 0 (
    echo [INFO] Port 8081 already in use, app may be running
    echo URL: http://localhost:8081/tutoring-management.html
    timeout /t 3 >nul
    exit /b 0
)

echo [START] Starting Spring Boot application...
echo [START] URL: http://localhost:8081/tutoring-management.html
echo [START] LAN: http://192.168.1.10:8081/tutoring-management.html
echo.
echo [TIP] Close this window to stop the app
echo.

java -Xms128m -Xmx512m -jar target\skt-server-1.0.0.jar

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] App failed to start, error code: %errorlevel%
    pause
)
