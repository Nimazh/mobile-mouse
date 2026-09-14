<#
.SYNOPSIS
    Mobile Mouse - Android Build Script
    Installs JDK 17, Android SDK CLI tools, and builds the debug APK.
    No Android Studio required.
#>

$ErrorActionPreference = "Stop"
$SDK_DIR   = "C:\Android\sdk"
$JDK_DIR   = "C:\Android\jdk17"
$BUILD_DIR = $PSScriptRoot

function Log-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Log-OK($msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Log-Warn($msg) { Write-Host "    [!!] $msg" -ForegroundColor Yellow }
function Log-Err($msg)  { Write-Host "    [ERR] $msg" -ForegroundColor Red; exit 1 }

# ── Step 1: JDK 17 ──────────────────────────────────────────────────────────
Log-Step "Checking JDK 17..."

$needJdk = $true
if (Test-Path "$JDK_DIR\bin\java.exe") {
    $v = & "$JDK_DIR\bin\java.exe" -version 2>&1 | Select-String "17\."
    if ($v) { Log-OK "JDK 17 already at $JDK_DIR"; $needJdk = $false }
}
if ($needJdk) {
    # Try system java first
    try {
        $v = java -version 2>&1 | Select-String "17\.|21\."
        if ($v) { Log-OK "JDK 17+ found in PATH"; $needJdk = $false }
    } catch {}
}

if ($needJdk) {
    Log-Step "Downloading JDK 17 (Adoptium / Eclipse Temurin)..."
    $jdkZip = "$env:TEMP\jdk17.zip"
    $jdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_windows_hotspot_17.0.11_9.zip"
    
    Write-Host "    Downloading ~180MB..."
    Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing
    
    New-Item -ItemType Directory -Force $JDK_DIR | Out-Null
    Write-Host "    Extracting..."
    Expand-Archive -Path $jdkZip -DestinationPath "$env:TEMP\jdk17_extract" -Force
    $extracted = Get-ChildItem "$env:TEMP\jdk17_extract" | Select-Object -First 1
    Copy-Item "$($extracted.FullName)\*" $JDK_DIR -Recurse -Force
    Remove-Item $jdkZip -Force
    Remove-Item "$env:TEMP\jdk17_extract" -Recurse -Force
    Log-OK "JDK 17 installed to $JDK_DIR"
}

# Set JAVA_HOME for this session
if (Test-Path "$JDK_DIR\bin\java.exe") {
    $env:JAVA_HOME = $JDK_DIR
} else {
    # Already in PATH
    $env:JAVA_HOME = (Get-Command java).Source | Split-Path | Split-Path
}
Write-Host "    JAVA_HOME = $env:JAVA_HOME"

# ── Step 2: Android SDK Command-Line Tools ───────────────────────────────────
Log-Step "Checking Android SDK..."

$sdkmanager = "$SDK_DIR\cmdline-tools\latest\bin\sdkmanager.bat"
$needSdk = -not (Test-Path $sdkmanager)

if ($needSdk) {
    Log-Step "Downloading Android SDK Command-Line Tools (~130MB)..."
    $cltZip = "$env:TEMP\android-cmdtools.zip"
    $cltUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    
    Write-Host "    Downloading..."
    Invoke-WebRequest -Uri $cltUrl -OutFile $cltZip -UseBasicParsing
    
    $extractDir = "$env:TEMP\android-cmdtools"
    Expand-Archive -Path $cltZip -DestinationPath $extractDir -Force
    
    New-Item -ItemType Directory -Force "$SDK_DIR\cmdline-tools\latest" | Out-Null
    Copy-Item "$extractDir\cmdline-tools\*" "$SDK_DIR\cmdline-tools\latest" -Recurse -Force
    Remove-Item $cltZip -Force
    Remove-Item $extractDir -Recurse -Force
    Log-OK "Android CLI tools installed"
}

$env:ANDROID_HOME = $SDK_DIR
$env:PATH = "$SDK_DIR\cmdline-tools\latest\bin;$SDK_DIR\platform-tools;$env:PATH"

# ── Step 3: Accept licenses & install SDK packages ───────────────────────────
Log-Step "Installing SDK packages (platforms, build-tools)..."

$platformsDir = "$SDK_DIR\platforms\android-35"
if (-not (Test-Path $platformsDir)) {
    Write-Host "    Accepting licenses..."
    "y`ny`ny`ny`ny`ny`ny" | & $sdkmanager --licenses 2>&1 | Out-Null
    
    Write-Host "    Installing platform-tools, build-tools, android-35..."
    & $sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35" 2>&1 | 
        Where-Object { $_ -match "Downloading|Installing|done" } | 
        ForEach-Object { Write-Host "    $_" }
    Log-OK "SDK packages installed"
} else {
    Log-OK "SDK packages already installed"
}

# ── Step 4: Create local.properties ─────────────────────────────────────────
Log-Step "Configuring local.properties..."
$localProps = "$BUILD_DIR\local.properties"
Set-Content $localProps "sdk.dir=$($SDK_DIR -replace '\\','\\\\')"
Log-OK "local.properties created"

# ── Step 5: Build APK ────────────────────────────────────────────────────────
Log-Step "Building debug APK..."
Push-Location $BUILD_DIR
try {
    $env:JAVA_HOME = $JDK_DIR
    & ".\gradlew.bat" assembleDebug --stacktrace 2>&1 | Tee-Object -Variable buildOut | Write-Host
    
    $apk = Get-ChildItem -Recurse "app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($apk) {
        $destApk = "$BUILD_DIR\..\MobileMouse-debug.apk"
        Copy-Item $apk.FullName $destApk -Force
        Log-OK "APK built successfully!"
        Write-Host "`n  ╔═══════════════════════════════════════════╗" -ForegroundColor Green
        Write-Host "  ║  APK: MobileMouse-debug.apk               ║" -ForegroundColor Green
        Write-Host "  ║  Install: adb install MobileMouse-debug.apk║" -ForegroundColor Green
        Write-Host "  ╚═══════════════════════════════════════════╝" -ForegroundColor Green
    } else {
        Log-Err "Build failed — APK not found. Check output above."
    }
} finally {
    Pop-Location
}
