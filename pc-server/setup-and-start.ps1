# Photo AI Enhance PC v1.3: initial install and start (Windows PowerShell)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
Write-Host "Photo AI Enhance PC Server v1.3" -ForegroundColor Cyan

$tail = Join-Path $env:ProgramFiles "Tailscale\tailscale.exe"
if (!(Test-Path $tail)) {
    Write-Host "Install Tailscale first: https://tailscale.com/download/windows" -ForegroundColor Yellow
    throw "Tailscale is not installed."
}
& $tail ip -4 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Please sign in and enable Tailscale on your PC." }

if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 -m venv .venv
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    & python -m venv .venv
} else {
    Write-Host "Install Python 3.11 or 3.12: https://www.python.org/downloads/windows/" -ForegroundColor Yellow
    throw "Python was not found."
}
if ($LASTEXITCODE -ne 0) { throw "Python virtual environment creation failed" }
$python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
& $python -m pip install --disable-pip-version-check -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw "Installing Pillow failed" }

$engine = Join-Path $PSScriptRoot "engine"
$binary = Get-ChildItem -Path $engine -Recurse -Filter "realesrgan-ncnn-vulkan.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $binary) {
    New-Item -ItemType Directory -Force -Path $engine | Out-Null
    $zip = Join-Path $PSScriptRoot "realesrgan-ncnn-vulkan-windows.zip"
    Write-Host "Downloading official Real-ESRGAN Vulkan engine (~45 MB)..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip" -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $engine -Force
    Remove-Item -Path $zip -Force
}
$binary = Get-ChildItem -Path $engine -Recurse -Filter "realesrgan-ncnn-vulkan.exe" | Select-Object -First 1
if ($null -eq $binary) { throw "Real-ESRGAN Vulkan engine missing after extracting archive" }
$model = Join-Path $binary.Directory.FullName "models\realesrgan-x4plus.param"
if (!(Test-Path $model)) { throw "The official archive does not contain realesrgan-x4plus model" }

Write-Host "Starting local Tailscale-only server..." -ForegroundColor Green
Write-Host "If Windows Firewall blocks access, allow inbound TCP 8765 on the Tailscale interface only." -ForegroundColor Yellow
& $python server.py
if ($LASTEXITCODE -ne 0) { throw "Server stopped with an error" }
