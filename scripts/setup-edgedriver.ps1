$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$driversDir = Join-Path $projectRoot ".drivers"
$zipPath = Join-Path $driversDir "edgedriver_win64.zip"

$edgeVersion = (Get-ItemProperty -Path "HKCU:\Software\Microsoft\Edge\BLBeacon" -ErrorAction SilentlyContinue).version
if (-not $edgeVersion) {
    $edgeVersion = (Get-ItemProperty -Path "HKLM:\Software\Microsoft\Edge\BLBeacon" -ErrorAction SilentlyContinue).version
}
if (-not $edgeVersion) {
    throw "Could not detect Microsoft Edge version from registry."
}

$downloadUrl = "https://msedgedriver.microsoft.com/$edgeVersion/edgedriver_win64.zip"

New-Item -ItemType Directory -Force -Path $driversDir | Out-Null
Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
Expand-Archive -Path $zipPath -DestinationPath $driversDir -Force

$driverPath = Join-Path $driversDir "msedgedriver.exe"
if (-not (Test-Path $driverPath)) {
    $driverPath = Join-Path $driversDir "edgedriver_win64\msedgedriver.exe"
}
if (-not (Test-Path $driverPath)) {
    throw "EdgeDriver executable not found after extraction in $driversDir"
}

Write-Host ""
Write-Host "EdgeDriver ready: $driverPath"
Write-Host "Optional for current shell:"
Write-Host ('$env:EDGEDRIVER_PATH="{0}"' -f $driverPath)




