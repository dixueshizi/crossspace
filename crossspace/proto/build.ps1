# 跨空间选择器原型 —— 手工编译打包脚本
# 依赖：JDK(含 javac/keytool)、Android build-tools、平台 android.jar、Xposed API(lib/api-82.jar)
# 可用环境变量覆盖：ANDROID_JAR / BUILD_TOOLS / JAVA_HOME / R8_JAR
# 未设置时尝试从 ANDROID_HOME / ANDROID_SDK_ROOT 自动探测 build-tools 与 android-35。

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "" }

$buildTools = if ($env:BUILD_TOOLS) { $env:BUILD_TOOLS } else { "" }
$androidJar = if ($env:ANDROID_JAR) { $env:ANDROID_JAR } else { "" }
$javaHome   = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "" }

# 自动探测：SDK 下最新的 build-tools
if (-not $buildTools -and $sdk) {
    $btRoot = Join-Path $sdk "build-tools"
    if (Test-Path $btRoot) {
        $bt = Get-ChildItem -Path $btRoot -Directory -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending | Select-Object -First 1
        if ($bt) { $buildTools = $bt.FullName }
    }
}

# 自动探测：SDK 下的 android-35 / android-34 平台 jar
if (-not $androidJar -and $sdk) {
    foreach ($v in @("android-35", "android-34")) {
        $p = Join-Path $sdk "platforms\$v\android.jar"
        if (Test-Path $p) { $androidJar = $p; break }
    }
}

if (-not $javaHome) {
    throw "请设置 JAVA_HOME（JDK 或 Android Studio 自带 jbr），或安装 JDK 后把 java 加入 PATH"
}

foreach ($p in @($buildTools, $androidJar, "$javaHome\bin\javac.exe")) {
    if (-not $p) { throw "缺少依赖：未设置 BUILD_TOOLS / ANDROID_JAR / JAVA_HOME" }
    if (-not (Test-Path $p)) { throw "缺少依赖：$p" }
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

$work = Join-Path $root "build"
if (Test-Path $work) {
    try { Remove-Item $work -Recurse -Force -ErrorAction Stop }
    catch { [IO.Directory]::Delete($work, $true) }
}
New-Item -ItemType Directory -Path $work | Out-Null

# 1. javac
$src = Join-Path $root "src"
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "[1/7] javac $($sources.Count) file(s)"
& "$javaHome\bin\javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options `
    -bootclasspath $androidJar `
    -classpath "$root\lib\api-82.jar" `
    -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 2. dex（优先用 Android Studio 自带的新版 r8.jar：build-tools 里的旧 R8
#    遇到"带外部实例的匿名内部类"会抛 NPE，已在 SpaceChooserActivity$1 复现）
Write-Host "[2/7] dex"
$jar = Join-Path $work "classes.jar"
& "$javaHome\bin\jar.exe" cf $jar -C $classes .
$dexdir = Join-Path $work "dex"
New-Item -ItemType Directory -Path $dexdir | Out-Null
$r8Jar = if ($env:R8_JAR) { $env:R8_JAR } else { "" }
if (-not $r8Jar) {
    foreach ($c in @(
            "$env:ProgramFiles\Android\Android Studio\plugins\android\lib\r8.jar",
            "${env:ProgramFiles(x86)}\Android\Android Studio\plugins\android\lib\r8.jar",
            "$env:LOCALAPPDATA\Programs\Android Studio\plugins\android\lib\r8.jar")) {
        if ($c -and (Test-Path $c)) { $r8Jar = $c; break }
    }
}
if ($r8Jar) {
    Write-Host "       using r8.jar: $r8Jar"
    & "$javaHome\bin\java.exe" -cp $r8Jar com.android.tools.r8.D8 `
        --min-api 29 --lib $androidJar --output $dexdir $jar
} else {
    Write-Host "       using d8.bat (build-tools)"
    & "$buildTools\d8.bat" --min-api 29 --lib $androidJar --output $dexdir $jar
}
if ($LASTEXITCODE -ne 0) { throw "dex failed" }

# 3. aapt2 compile（资源：res/values/arrays.xml 里的 LSPosed 推荐作用域数组）
Write-Host "[3/7] aapt2 compile res"
$res = Join-Path $root "res"
$resZip = Join-Path $work "res.zip"
$resArgs = @()
if (Test-Path $res) {
    & "$buildTools\aapt2.exe" compile --dir $res -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
    $resArgs = @($resZip)
} else {
    Write-Host "       no res/ directory, skipped"
}

# 4. aapt2 link（生成含二进制清单、资源表与 assets 的 APK）
Write-Host "[4/7] aapt2 link"
$base = Join-Path $work "base.apk"
& "$buildTools\aapt2.exe" link -o $base -I $androidJar `
    --manifest (Join-Path $root "AndroidManifest.xml") `
    -A (Join-Path $root "assets") `
    --auto-add-overlay @resArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 5. 塞入 classes.dex
Write-Host "[5/7] add classes.dex"
Push-Location $dexdir
try {
    & "$buildTools\aapt.exe" add $base "classes.dex"
    if ($LASTEXITCODE -ne 0) { throw "aapt add failed" }
} finally { Pop-Location }

# 6. zipalign
Write-Host "[6/7] zipalign"
$aligned = Join-Path $work "aligned.apk"
& "$buildTools\zipalign.exe" -f 4 $base $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# 7. 签名（首次自动生成调试密钥）
Write-Host "[7/7] apksigner"
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & "$javaHome\bin\keytool.exe" -genkeypair -v -keystore $ks -alias csproto `
        -keyalg RSA -keysize 2048 -validity 3650 `
        -storepass crossspace -keypass crossspace `
        -dname "CN=CrossSpace Proto, O=Local, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$out = Join-Path $root "crossspace-proto.apk"
& "$buildTools\apksigner.bat" sign --ks $ks --ks-pass pass:crossspace --key-pass pass:crossspace `
    --ks-key-alias csproto --out $out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "`nOK -> $out  ($((Get-Item $out).Length) bytes)"
Write-Host "安装：adb install -r `"$out`""
