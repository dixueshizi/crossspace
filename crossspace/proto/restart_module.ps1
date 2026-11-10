# 重装模块并让 LSPosed 重新加载（会 kill system_server，属于软重启）
#
# 为什么需要它：LSPosed 会缓存已加载的 dex，装完新 APK 后如果不重启 system_server，
# 跑的还是旧代码（表现为日志里 BOOT rev 一直不变）。
#
# 用法：powershell -File restart_module.ps1 -Rev <期望的版本串>
#       powershell -File restart_module.ps1 -SkipInstall 1   # 只重启，不重装
#
# 注意：会多次 kill -9 system_server，过程中手机不可用，请确保自己清楚在做什么。

param([string]$Rev = "", [string]$SkipInstall = "0", [string]$Adb = "adb")

$apk = Join-Path $PSScriptRoot "crossspace-proto.apk"
function Sh($c) { (& $Adb shell $c 2>&1 | Out-String).Trim() }
function SsPid { Sh 'su -c "pidof system_server"' }
function KillSS { $p = SsPid; if ($p -match '^\d+$') { Sh "su -c `"kill -9 $p`"" | Out-Null; Write-Host "killed $p" } }
function BootRev { Sh 'su -c "grep -a BOOT /data/system/crossspace.log | tail -n 1"' }

if ($SkipInstall -ne "1") {
    & $Adb install --no-incremental -r $apk 2>&1 | Select-Object -Last 1
}
Sh 'su -c "/data/adb/modules/zygisk_lsposed/lspctl stop"' | Write-Host
Start-Sleep -Seconds 2
KillSS
Write-Host "wait ss 85s"
Start-Sleep -Seconds 85
& $Adb shell 'su -c "setsid /data/adb/modules/zygisk_lsposed/daemon --force >/dev/null 2>&1 < /dev/null &"' 2>&1 | Out-Null
Start-Sleep -Seconds 9
Write-Host "lspd=$(Sh 'su -c "pidof lspd"')"
KillSS
Write-Host "wait prime 95s"
Start-Sleep -Seconds 95
Write-Host "status: $((Sh 'su -c "/data/adb/modules/zygisk_lsposed/lspctl status"') -split "`n" | Where-Object { $_ -match 'Modules|connected' })"
KillSS
Write-Host "wait 100s"
Start-Sleep -Seconds 100
$b = BootRev
Write-Host "boot: $b"
if ($Rev -ne "" -and $b -notlike "*$Rev*") {
    Write-Host "rev mismatch, one more kill"
    KillSS
    Start-Sleep -Seconds 100
    Write-Host "boot: $(BootRev)"
}
