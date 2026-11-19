param(
    [string]$ChromeMajor
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$driversDir = Join-Path $projectRoot ".drivers"
$zipPath = Join-Path $driversDir "chromedriver-win64.zip"
$extractDir = $driversDir

if (-not $ChromeMajor) {
    $installedVersion = (Get-ItemProperty -Path "HKCU:\Software\Google\Chrome\BLBeacon" -ErrorAction SilentlyContinue).version
    if (-not $installedVersion) {
        $installedVersion = (Get-ItemProperty -Path "HKLM:\Software\Google\Chrome\BLBeacon" -ErrorAction SilentlyContinue).version
    }

    if ($installedVersion) {
        $ChromeMajor = ($installedVersion -split "\\.")[0]
        Write-Host "Detected Chrome version: $installedVersion (major $ChromeMajor)"
    } else {
        throw "Could not detect Chrome version from registry. Pass -ChromeMajor manually."
    }
}

$driverVersionUrl = "https://googlechromelabs.github.io/chrome-for-testing/LATEST_RELEASE_$ChromeMajor"
$driverVersion = Invoke-RestMethod -Uri $driverVersionUrl
$downloadUrl = "https://storage.googleapis.com/chrome-for-testing-public/$driverVersion/win64/chromedriver-win64.zip"

New-Item -ItemType Directory -Force -Path $driversDir | Out-Null
Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

$driverPath = Join-Path $driversDir "chromedriver-win64\chromedriver.exe"
if (-not (Test-Path $driverPath)) {
    throw "Chromedriver was downloaded but executable was not found at $driverPath"
}

Write-Host ""
Write-Host "ChromeDriver ready: $driverPath"
Write-Host "Optional for current shell:"
Write-Host "$env:CHROMEDRIVER_PATH=\"$driverPath\""


