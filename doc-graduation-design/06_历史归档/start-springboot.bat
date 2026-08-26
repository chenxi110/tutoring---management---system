@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo   上课通教学管理系统 V1.0 启动脚本
echo ========================================
echo.

echo [1/3] 编译项目...
call mvn -o compile -q -s settings.xml
if %errorlevel% neq 0 (
    echo [错误] 编译失败，请检查JDK和Maven环境
    pause
    exit /b 1
)

echo [2/3] 构建classpath...
powershell -ExecutionPolicy Bypass -Command "$jars = Get-ChildItem -Path $env:USERPROFILE\.m2\repository -Filter '*.jar' -Recurse | Where-Object { $_.FullName -notmatch '(sources|javadoc|tests)' -and $_.FullName -notmatch 'slf4j-api\\(1\.|2\.0\.[0-8])' -and $_.FullName -notmatch 'spring-(core|jcl|beans|context|expression|web|webmvc|jdbc|tx)\\6\.0\.' -and $_.FullName -notmatch 'logback-(classic|core)\\1\.[0-3]\.' -and $_.FullName -notmatch 'jackson.*\\2\.1[0-4]\.' }; '%CD%\target\classes;' + (($jars | Select-Object -ExpandProperty FullName) -join ';') | Set-Content target\classpath.txt -Encoding UTF8"

echo [3/3] 启动SpringBoot服务...
echo.
echo 浏览器访问地址: http://localhost:8081/tutoring-management.html
echo 默认账号: admin / admin123
echo.
echo 按 Ctrl+C 停止服务
echo ========================================
echo.

for /f "delims=" %%a in (target\classpath.txt) do set CP=%%a
java -cp "%CP%" com.skt.SktApplication
pause
