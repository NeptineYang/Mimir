<#
.SYNOPSIS
    真机证据采集：一条命令把定位 P1–P5 失败所需的信息收进一个文本文件。

.DESCRIPTION
    Windows PowerShell 没有 grep，之前给的 bash 命令不能直接抄。本脚本用 PowerShell 原生命令
    完成同样的过滤，并把结果写到单个文件，方便整份回传。

    采集内容：
      1. 设备与系统版本（机型 / Android / HyperOS / Shizuku 版本需要你手动补）
      2. Mimir 相关进程（重点看 com.neptine.mimir:shell 是否存在）
      3. 无障碍服务是否真的绑定成功
      4. 是否有残留的虚拟屏（mimir-*）
      5. 前台窗口与所在显示（判断目标 App 到底在哪个屏上）
      6. 与设备层相关的日志（Shizuku / MimirShell / MimirAgent / 崩溃）

.PARAMETER Out
    输出文件路径。默认写到 build\device-log-<时间戳>.txt

.PARAMETER LogLines
    日志最多保留多少行，默认 3000。注意这是"输出截断"，不是"读取窗口"——
    读取时会覆盖整个 logcat 缓冲区，避免 tag 过滤作用不到 Mimir 的日志上。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\collect-device-logs.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\collect-device-logs.ps1 -Out D:\mimir-log.txt
#>
param(
    [string]$Out = "",
    [int]$LogLines = 3000
)

$ErrorActionPreference = "Continue"

function Find-Adb {
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }

    $localProps = Join-Path (Get-Location) "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match "^\s*sdk\.dir\s*=" } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace "^\s*sdk\.dir\s*=\s*", "") -replace "\\\\", "\"
            $candidate = Join-Path $sdk "platform-tools\adb.exe"
            if (Test-Path $candidate) { return $candidate }
        }
    }

    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    return $null
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $result = & $script:AdbPath @AdbArgs 2>&1
    return (($result | ForEach-Object { "$_" }) -join "`r`n")
}

function Add-Text {
    param([string]$Text)
    Add-Content -Path $script:OutPath -Value $Text -Encoding UTF8
}

function Add-Section {
    param([string]$Title, [string]$Body)
    Add-Text ""
    Add-Text ("=" * 70)
    Add-Text ("## " + $Title)
    Add-Text ("=" * 70)
    if ([string]::IsNullOrWhiteSpace($Body)) {
        Add-Text "(无输出)"
    } else {
        Add-Text $Body
    }
}

# ---------- 定位 adb ----------
$script:AdbPath = Find-Adb
if (-not $script:AdbPath) {
    Write-Host "找不到 adb.exe。" -ForegroundColor Red
    Write-Host "请设置环境变量 ADB 指向 adb.exe，或确认 local.properties 里的 sdk.dir 正确。"
    exit 1
}
Write-Host ("使用 adb: " + $script:AdbPath)

# ---------- 输出路径 ----------
if ([string]::IsNullOrWhiteSpace($Out)) {
    $buildDir = Join-Path (Get-Location) "build"
    if (-not (Test-Path $buildDir)) { New-Item -ItemType Directory -Path $buildDir | Out-Null }
    $Out = Join-Path $buildDir ("device-log-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
}
$script:OutPath = $Out
if (Test-Path $script:OutPath) { Remove-Item $script:OutPath -Force }

# ---------- 设备检查 ----------
$devices = Invoke-Adb @("devices", "-l")
Add-Section "adb devices" $devices

if ($devices -match "unauthorized") {
    Write-Host "设备处于 unauthorized：请在手机上点「允许 USB 调试」。" -ForegroundColor Yellow
    Write-Host ("结果已写入: " + $script:OutPath)
    exit 1
}
if (-not ($devices -match "\bdevice\b")) {
    Write-Host "没有检测到已授权的设备。" -ForegroundColor Yellow
    Write-Host ("结果已写入: " + $script:OutPath)
    exit 1
}

# ---------- 1. 系统信息 ----------
$sysInfo = @(
    (Invoke-Adb @("shell", "getprop", "ro.product.model")),
    (Invoke-Adb @("shell", "getprop", "ro.build.version.release")),
    (Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")),
    (Invoke-Adb @("shell", "getprop", "ro.mi.os.version.name")),
    (Invoke-Adb @("shell", "getprop", "ro.miui.ui.version.name"))
) -join "`r`n"
Add-Section "机型 / Android / HyperOS 版本（mi.os / miui 可能为空，属正常）" $sysInfo

# ---------- 2. 进程 ----------
$psAll = Invoke-Adb @("shell", "ps", "-A")
$mimirProcs = ($psAll -split "`r?`n" | Where-Object { $_ -match "neptine" }) -join "`r`n"
Add-Section "Mimir 相关进程（关键：应出现 com.neptine.mimir:shell）" $mimirProcs

# ---------- 3. 无障碍 ----------
$accEnabled = Invoke-Adb @("shell", "settings", "get", "secure", "enabled_accessibility_services")
$accGlobal = Invoke-Adb @("shell", "settings", "get", "secure", "accessibility_enabled")
$accBound = Invoke-Adb @("shell", "dumpsys", "accessibility")
$boundLines = ($accBound -split "`r?`n" | Select-String -Pattern "Bound services|Enabled services|Service\[" | ForEach-Object { "$_" }) -join "`r`n"
Add-Section "无障碍开关与已绑定服务" ("enabled_accessibility_services = " + $accEnabled + "`r`naccessibility_enabled = " + $accGlobal + "`r`n" + $boundLines)

# ---------- 4. 虚拟屏 ----------
$displayDump = Invoke-Adb @("shell", "dumpsys", "display")
$mimirDisplays = ($displayDump -split "`r?`n" | Where-Object { $_ -match "mimir-" }) -join "`r`n"
$mimirDisplayCount = ($displayDump -split "`r?`n" | Where-Object { $_ -match "mimir-" }).Count
Add-Section ("残留虚拟屏（命中行数 " + $mimirDisplayCount + "，正常应为 0）") $mimirDisplays

# ---------- 5. 前台与显示归属 ----------
# 真机上"App 到底在哪个屏"决定了任务能否注入；dumpsys 的这两行是唯一不用装额外工具的证据。
$windowDump = Invoke-Adb @("shell", "dumpsys", "window", "displays")
$focusLines = ($windowDump -split "`r?`n" |
    Select-String -Pattern "mCurrentFocus|mFocusedApp|Display: mDisplayId" |
    ForEach-Object { "$_" }) -join "`r`n"
Add-Section "前台窗口与所在显示（mCurrentFocus / mFocusedApp）" $focusLines

# ---------- 6. 日志 ----------
# 不能用 "logcat -d -t N -s TAG"：-t 是先在全缓冲区截最后 N 行、再套 tag 过滤，
# Mimir 的日志根本不在那 N 行里，结果永远是空的（V1 真机采集两次都是空段）。
# 正确做法是不带 -t，让 tag 过滤器作用在整个缓冲区上，再在本地截行数。
$logcat = Invoke-Adb @(
    "logcat", "-d",
    "-s", "ShizukuService:V", "ShizukuServiceStarter:V", "MimirShell:V",
    "MimirAgent:D", "MimirTree:D", "MimirA11y:D", "AndroidRuntime:E"
)
if (-not [string]::IsNullOrWhiteSpace($logcat)) {
    $logcat = (($logcat -split "`r?`n" | Select-Object -Last $LogLines) -join "`r`n")
} else {
    $logcat = "（缓冲区里没有这些 tag 的日志：可能是刚重启过设备，或采集前日志已轮转。重跑一次任务后立刻采集）"
}
Add-Section ("日志（相关 tag 最近 " + $LogLines + " 行）") $logcat

Write-Host ""
Write-Host "采集完成：" -ForegroundColor Green
Write-Host ("  " + $script:OutPath)
Write-Host "把这个文件整份发回即可，不需要手动裁剪。"
