# AlibabaProtect 彻底清除脚本（需在安全模式下以管理员运行）
# 使用方法：
#   1. 按 Win+I → 系统 → 恢复 → 高级启动 → 立即重新启动
#   2. 选择 疑难解答 → 高级选项 → 启动设置 → 重启
#   3. 按 4 或 F4 进入安全模式
#   4. 以管理员身份运行 PowerShell，执行此脚本

Write-Host "===== AlibabaProtect 彻底清除 =====" -ForegroundColor Cyan

# 1. 停止并删除 AlibabaProtect 服务
Write-Host "`n[1/5] 停止 AlibabaProtect 服务..."
sc.exe stop AlibabaProtect 2>$null
Start-Sleep -Seconds 2
sc.exe delete AlibabaProtect 2>$null
Write-Host "AlibabaProtect 服务已删除"

# 2. 停止并删除 AliPaladin 内核驱动
Write-Host "`n[2/5] 停止 AliPaladin 内核驱动..."
sc.exe stop AliPaladin 2>$null
Start-Sleep -Seconds 2
sc.exe config AliPaladin start= disabled 2>$null
sc.exe delete AliPaladin 2>$null
Write-Host "AliPaladin 驱动已删除"

# 3. 强制结束进程
Write-Host "`n[3/5] 结束 AlibabaProtect 进程..."
taskkill /F /IM AlibabaProtect.exe 2>$null
taskkill /F /IM AliProtectUpdate.exe 2>$null
taskkill /F /IM RestartService.exe 2>$null
Write-Host "进程已结束"

# 4. 删除驱动文件
Write-Host "`n[4/5] 删除驱动文件..."
$driverFiles = @(
    "C:\WINDOWS\system32\drivers\AliPaladinEx64.sys",
    "C:\Program Files (x86)\AlibabaProtect\1.0.70.2161\AliPaladinEx64.sys",
    "C:\Program Files (x86)\AlibabaProtect\1.0.70.2161\AliPaladin.sys",
    "C:\Program Files (x86)\AlibabaProtect\1.0.70.2161\AliPaladin64_win10.sys",
    "C:\Program Files (x86)\AlibabaProtect\1.0.70.2161\AliPaladin64_win7.sys",
    "C:\Program Files (x86)\AlibabaProtect\1.0.70.2161\AliPaladin_win10.sys",
    "C:\Program Files (x86)\AlibabaProtect\1.0.70.2161\AliPaladin_win7.sys"
)
foreach ($f in $driverFiles) {
    if (Test-Path $f) {
        Remove-Item $f -Force -ErrorAction SilentlyContinue
        Write-Host "  已删除: $f"
    }
}

# 5. 删除程序目录
Write-Host "`n[5/5] 删除 AlibabaProtect 程序目录..."
$programDir = "C:\Program Files (x86)\AlibabaProtect"
if (Test-Path $programDir) {
    Remove-Item $programDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  已删除: $programDir"
}

# 清理注册表残留
Write-Host "`n清理注册表..."
$regPaths = @(
    "HKLM:\SYSTEM\CurrentControlSet\Services\AlibabaProtect",
    "HKLM:\SYSTEM\CurrentControlSet\Services\AliPaladin"
)
foreach ($reg in $regPaths) {
    if (Test-Path $reg) {
        Remove-Item $reg -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "  已清理: $reg"
    }
}

# 验证结果
Write-Host "`n===== 验证结果 =====" -ForegroundColor Green
$svc1 = Get-CimInstance Win32_Service -Filter "Name='AlibabaProtect'" -ErrorAction SilentlyContinue
$svc2 = Get-CimInstance Win32_SystemDriver -Filter "Name='AliPaladin'" -ErrorAction SilentlyContinue
$proc = Get-Process -Name "AlibabaProtect" -ErrorAction SilentlyContinue

if ($svc1) { Write-Host "AlibabaProtect 服务: 仍存在 ($($svc1.State))" -ForegroundColor Red }
else { Write-Host "AlibabaProtect 服务: 已删除" -ForegroundColor Green }

if ($svc2) { Write-Host "AliPaladin 驱动: 仍存在 ($($svc2.State))" -ForegroundColor Red }
else { Write-Host "AliPaladin 驱动: 已删除" -ForegroundColor Green }

if ($proc) { Write-Host "AlibabaProtect 进程: 仍在运行" -ForegroundColor Red }
else { Write-Host "AlibabaProtect 进程: 已结束" -ForegroundColor Green }

Write-Host "`n完成！请重启电脑回到正常模式。" -ForegroundColor Cyan
Read-Host "按回车键退出"
