# 上课通应用监控脚本 - PowerShell版本
# 每20秒检查一次8081端口，崩溃自动用javaw.exe重启

$ErrorActionPreference = "SilentlyContinue"
$appDir = "C:\Users\luoch\Desktop\上课通"
$jarPath = Join-Path $appDir "target\skt-server-1.0.0.jar"
$javawPath = "F:\JAVA\jdk-24_windows-x64_bin\jdk-24.0.2\bin\javaw.exe"
$logPath = Join-Path $appDir "monitor-ps.log"
$checkInterval = 20  # 秒

function Write-Log {
    param([string]$msg)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$timestamp] $msg"
    Add-Content -Path $logPath -Value $line -Encoding UTF8
    Write-Output $line
}

function Test-PortListening {
    param([int]$port)
    $result = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
    return ($result -ne $null)
}

function Start-App {
    Write-Log "正在启动应用（javaw.exe）..."
    Set-Location $appDir
    Start-Process -FilePath $javawPath -ArgumentList "-Xms128m","-Xmx256m","-jar",$jarPath -WindowStyle Hidden
    Start-Sleep -Seconds 15
    if (Test-PortListening 8081) {
        Write-Log "✅ 应用启动成功，8081端口已监听"
        return $true
    } else {
        Write-Log "❌ 应用启动后端口未监听，等待10秒重试..."
        Start-Sleep -Seconds 10
        if (Test-PortListening 8081) {
            Write-Log "✅ 应用启动成功（延迟）"
            return $true
        }
        Write-Log "❌ 应用启动失败"
        return $false
    }
}

Write-Log "========================================"
Write-Log "上课通监控脚本启动，检查间隔: $checkInterval 秒"
Write-Log "应用目录: $appDir"
Write-Log "========================================"

# 首次检查，如果未运行则启动
if (-not (Test-PortListening 8081)) {
    Write-Log "首次检查: 应用未运行，启动中..."
    Start-App
} else {
    Write-Log "首次检查: 应用已在运行"
}

# 持续监控循环
while ($true) {
    Start-Sleep -Seconds $checkInterval
    
    $portOk = Test-PortListening 8081
    $javaProc = Get-Process -Name javaw -ErrorAction SilentlyContinue
    
    if (-not $portOk) {
        Write-Log "⚠️ 检测到8081端口未监听！"
        if ($javaProc) {
            Write-Log "Java进程存在(PID $($javaProc.Id))但端口未监听，可能启动中，等待10秒..."
            Start-Sleep -Seconds 10
            if (Test-PortListening 8081) {
                Write-Log "✅ 端口恢复监听"
                continue
            }
        }
        Write-Log "应用已崩溃，正在自动重启..."
        # 清理残留进程
        if ($javaProc) {
            Stop-Process -Name javaw -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
        }
        Start-App
    } else {
        # 端口正常，静默运行（每5分钟记录一次心跳）
        $minute = (Get-Date).Minute
        if ($minute % 5 -eq 0 -and (Get-Date).Second -lt 25) {
            $mem = if ($javaProc) { [math]::Round($javaProc.WorkingSet64/1MB,1) } else { "N/A" }
            Write-Log "💓 心跳: 应用正常运行，内存 ${mem}MB"
        }
    }
}
