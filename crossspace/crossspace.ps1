# ============================================================
# crossspace.ps1 —— Windows 侧薄封装
# 把「应用 + 目标空间 + 页面」翻译成设备端的 am start --user N，
# 经 adb 执行 crossspace.sh；默认用 shell 身份，失败时自动 su 提权。
#
# 常用：
#   .\crossspace.ps1 -Deploy
#   .\crossspace.ps1 -Users
#   .\crossspace.ps1 -User 999 -App wx -Page home -Foreground
#   .\crossspace.ps1 -User 10  -App alipay -Page scan
#   .\crossspace.ps1 -All -App wx -Page home -Range 10-30
#   .\crossspace.ps1 -User 10 -Pkg com.tencent.mm -Component com.tencent.mm/.ui.LauncherUI
#   .\crossspace.ps1 -Back                                 从分身界面回到主空间
#
# -Foreground：启动后确认顶层 Activity 已切到目标空间；999 首次可能弹出 ColorOS
#   多开选择器，勾选“记住我的选择”一次后即可自动直达。
#
# PC 侧退出码：64 参数错误 / 72 没有 adb / 73 没有设备 / 74 推送失败 / 76 设备端执行失败
# 设备侧退出码：65 用户不存在 / 66 未安装应用 / 67 无可用组件 / 68 权限不足 / 69 am 失败
#              77 已启动但未切到目标空间前台
# ============================================================

param(
    [string]$User,
    [string]$App = 'wx',
    [string]$Page = 'home',
    [string]$Pkg,
    [string]$Component,
    [string]$Range,
    [switch]$All,
    [switch]$Users,
    [switch]$Apps,
    [switch]$Env,
    [switch]$Back,
    [switch]$Deploy,
    [switch]$Foreground,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$Su
)

$ErrorActionPreference = 'Stop'

$DevDir = '/data/local/tmp/crossspace'
$DevSh = $DevDir + '/crossspace.sh'

function Show-Fail {
    param([int]$Code, [string]$Message)
    Write-Host ('[E' + $Code + '] ' + $Message) -ForegroundColor Red
    exit $Code
}

function Q {
    param([string]$s)
    if ($s -match '^[A-Za-z0-9_./:@=+-]+$') { return $s }
    return "'" + $s + "'"
}

function Confirm-Adb {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Show-Fail 72 '找不到 adb。请安装 platform-tools 并把 adb 加入 PATH，或在 platform-tools 目录下执行'
    }
    $lines = @(& adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+device$' })
    if ($lines.Count -eq 0) {
        $raw = ((& adb devices 2>$null | Select-Object -Skip 1) -join ' ').Trim()
        Show-Fail 73 ('没有已授权的设备（adb devices 显示：' + $raw + '）。请连接手机并允许 USB 调试')
    }
}

function Invoke-Device {
    param([string]$Inner)

    if ($Su) {
        & adb shell ('su -c "' + $Inner + '"')
        $rc = $LASTEXITCODE
    }
    else {
        & adb shell $Inner
        $rc = $LASTEXITCODE
        # 只有在“命令根本没能执行”时才重试 su；64-71 是脚本自己的业务错误码，不重试
        if ($rc -eq 1 -or $rc -eq 126 -or $rc -eq 127 -or $rc -eq 255) {
            Write-Host '（shell 身份执行失败，尝试 su 提权重试…）' -ForegroundColor Yellow
            & adb shell ('su -c "' + $Inner + '"')
            $rc = $LASTEXITCODE
        }
    }

    if ($rc -ne 0) { exit $rc }
}

function Show-Usage {
    Write-Host ''
    Write-Host 'crossspace —— ColorOS 跨空间拉起应用（adb 即可，root 可选）'
    Write-Host ''
    Write-Host '  .\crossspace.ps1 -Deploy                              首次部署到手机'
    Write-Host '  .\crossspace.ps1 -Users                               列出空间及微信/支付宝安装情况'
    Write-Host '  .\crossspace.ps1 -Apps                                列出应用名单与页面映射'
    Write-Host '  .\crossspace.ps1 -Env                                 版本/环境信息'
    Write-Host '  .\crossspace.ps1 -User 999 -App wx -Page home         微信主界面到 999 空间'
    Write-Host '  .\crossspace.ps1 -User 999 -App wx -Page home -Foreground  启动并确保界面在前台'
    Write-Host '  .\crossspace.ps1 -Back                                从分身界面回到主空间'
    Write-Host '  .\crossspace.ps1 -User 10  -App alipay -Page scan     支付宝扫一扫到 10 号空间'
    Write-Host '  .\crossspace.ps1 -All -App wx -Page home -Range 10-30 批量'
    Write-Host '  .\crossspace.ps1 -User 10 -Pkg <包名> -Component <pkg/Activity>'
    Write-Host ''
    Write-Host '可选：-Foreground 启动后确认/切到目标空间前台；-DryRun 只打印命令'
    Write-Host '      -Force 跳过安装检查；-Su 强制以 root 执行'
    Write-Host '页面：home / scan / sns(微信) / paycode(支付宝) / 完整组件名'
    Write-Host ''
}

function Do-Deploy {
    Confirm-Adb
    $localSh = Join-Path $PSScriptRoot 'crossspace.sh'
    if (-not (Test-Path $localSh)) {
        Show-Fail 74 '本地缺少 crossspace.sh（应与本脚本放在同一目录）'
    }
    & adb shell ('mkdir -p ' + $DevDir) | Out-Null
    & adb push $localSh $DevSh | Out-Null
    if ($LASTEXITCODE -ne 0) { Show-Fail 74 '推送 crossspace.sh 失败' }

    $localConf = Join-Path $PSScriptRoot 'apps.conf'
    if (Test-Path $localConf) {
        & adb push $localConf ($DevDir + '/apps.conf') | Out-Null
    }
    & adb shell ('chmod 755 ' + $DevSh) | Out-Null

    Write-Host ('已部署：' + $DevSh)
    Invoke-Device ('sh ' + $DevSh + ' env')
}

# ---------- 入口 ----------

if ($Deploy) { Do-Deploy; exit 0 }

if (-not $User -and -not $All -and -not $Users -and -not $Apps -and -not $Env -and -not $Back) {
    Show-Usage
    exit 64
}

Confirm-Adb

if ($Users) { Invoke-Device ('sh ' + $DevSh + ' users'); exit 0 }
if ($Apps) { Invoke-Device ('sh ' + $DevSh + ' apps'); exit 0 }
if ($Env) { Invoke-Device ('sh ' + $DevSh + ' env'); exit 0 }
if ($Back) { Invoke-Device ('sh ' + $DevSh + ' home'); exit 0 }

if ($All -and $Foreground) {
    Show-Fail 64 '-All 不能和 -Foreground 一起用（批量逐个切前台会打断当前使用）'
}

$pre = ''
if ($DryRun) { $pre = 'CROSSSPACE_DRYRUN=1 ' }
if ($Force) { $pre = $pre + 'CROSSSPACE_FORCE=1 ' }
if ($Foreground) { $pre = $pre + 'CROSSSPACE_FOREGROUND=1 ' }

if ($All) {
    $cmd = 'sh ' + $DevSh + ' all ' + (Q $App) + ' ' + (Q $Page)
    if ($Range) { $cmd = $cmd + ' ' + (Q $Range) }
    Invoke-Device ($pre + $cmd)
    exit 0
}

if ($Component) {
    if (-not $User) { Show-Fail 64 '-Component 需要同时指定 -User' }
    if (-not $Pkg) { Show-Fail 64 '-Component 需要同时指定 -Pkg <包名>' }
    Invoke-Device ($pre + 'sh ' + $DevSh + ' ' + (Q $User) + ' component ' + (Q $Pkg) + ' ' + (Q $Component))
    exit 0
}

Invoke-Device ($pre + 'sh ' + $DevSh + ' ' + (Q $User) + ' ' + (Q $App) + ' ' + (Q $Page))
exit 0
