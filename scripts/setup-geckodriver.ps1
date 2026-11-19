param(
    [string]$GeckoVersion = "v0.36.0"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$driversDir = Join-Path $projectRoot ".drivers"
$zipPath = Join-Path $driversDir "geckodriver-v0.36.0-win64.zip"
$extractDir = Join-Path $driversDir "geckodriver"

$downloadUrl = "https://github.com/mozilla/geckodriver/releases/download/$GeckoVersion/geckodriver-$GeckoVersion-win64.zip"

New-Item -ItemType Directory -Force -Path $driversDir | Out-Null
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

$driverPath = Join-Path $extractDir "geckodriver.exe"
if (-not (Test-Path $driverPath)) {
    throw "GeckoDriver executable not found at $driverPath"
}

Write-Host ""
Write-Host "GeckoDriver ready: $driverPath"
Write-Host "Optional for current shell:"
Write-Host "$env:GECKODRIVER_PATH=\"$driverPath\""

