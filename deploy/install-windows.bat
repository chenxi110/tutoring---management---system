@echo off
chcp 65001 >nul
setlocal enableDelayedExpansion
cd /d "%~dp0\.."

REM ============================================================
REM 上课通 Windows 开机自启动安装脚本（WinSW 系统服务）
REM 用法：右键“以管理员身份运行”本脚本
REM 前置：JDK17+、Maven、MySQL 已安装；需下载 WinSW-x64.exe
REM ============================================================

REM 自动申请管理员权限
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo 需要管理员权限，正在提权...
  powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

echo === 上课通 Windows 服务安装 ===

echo [1/4] 构建 jar 包...
call mvn -DskipTests package -q -s settings.xml
if %errorlevel% neq 0 (
  echo [错误] 构建失败，请检查 JDK / Maven 环境
  pause
  exit /b 1
)

set APP_DIR=%CD%
set JAR=skt-server-1.0.0.jar

echo [2/4] 配置 Agnes-AI 密钥（写入系统环境变量，不进 jar、不进代码）...
set /p AI_KEY=请输入 Agnes-AI API Key (sk-...):
if "%AI_KEY%"=="" (
  echo [警告] 未输入密钥，AI 功能将不可用。可稍后用 setx AI_API_KEY "sk-xxx" /M 补设
) else (
  setx AI_API_KEY "%AI_KEY%" /M >nul
  echo 已设置系统环境变量 AI_API_KEY
)
setx AI_BASE_URL "https://apihub.agnes-ai.com/v1" /M >nul
setx AI_MODEL "agnes-ai" /M >nul

echo [3/4] 检查 WinSW 可执行文件...
if not exist "%APP_DIR%\skt-server.exe" (
  echo [错误] 未找到 skt-server.exe
  echo 请下载 WinSW-x64.exe: https://github.com/winsw/winsw/releases
  echo   重命名为 skt-server.exe 放到项目根目录后重新运行本脚本。
  echo 也可改用“任务计划程序”开机启动 java -jar（见部署上线文档）。
  pause
  exit /b 1
)

echo [4/4] 安装并启动系统服务...
skt-server.exe install
if %errorlevel% neq 0 (
  echo [警告] 服务可能已存在，尝试卸载后重装
  skt-server.exe uninstall >nul 2>&1
  skt-server.exe install
)
skt-server.exe start

echo === 安装完成 ===
echo 服务已注册为 Windows 系统服务（开机自启动、崩溃自动重启）
echo 管理: skt-server.exe stop ^| uninstall ^| status
echo 日志: %APP_DIR%\logs\
pause
