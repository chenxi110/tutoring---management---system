@echo off
cd /d "%~dp0"
java -Xms128m -Xmx512m -jar target\skt-server-1.0.0.jar >> "%~dp0logs\app.log" 2>&1
