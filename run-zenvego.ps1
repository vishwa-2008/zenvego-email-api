$ErrorActionPreference = 'Stop'

$backendRoot = 'D:\email-otp-bot'
$frontendRoot = 'D:\Zenvego-main\Zenvego-main'
$backendUrl = 'http://localhost:8080/health'

function Test-ListeningPort([int]$Port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

if (-not (Test-Path "$backendRoot\run-backend.ps1")) {
    throw "Backend launcher not found: $backendRoot\run-backend.ps1"
}
if (-not (Test-Path "$frontendRoot\package.json")) {
    throw "Zenvego frontend not found: $frontendRoot"
}

if (-not (Test-ListeningPort 8080)) {
    Write-Host 'Starting the email OTP backend on port 8080...' -ForegroundColor Cyan
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$backendRoot\run-backend.ps1" `
        -WorkingDirectory $backendRoot `
        -WindowStyle Hidden
}

$backendReady = $false
for ($attempt = 1; $attempt -le 15; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri $backendUrl -TimeoutSec 2
        if ($health.status -eq 'ok') {
            $backendReady = $true
            break
        }
    } catch {}
    Start-Sleep -Seconds 1
}

if (-not $backendReady) {
    throw 'The email OTP backend did not start on port 8080. Check D:\email-otp-bot\.env for SMTP settings.'
}

if (Test-ListeningPort 3000) {
    throw 'Port 3000 is already in use. Close the existing Zenvego terminal first, then run this command again.'
}

Write-Host 'Email OTP backend is ready.' -ForegroundColor Green
Write-Host 'Starting Zenvego at http://localhost:3000 ...' -ForegroundColor Green
Set-Location $frontendRoot
& npm.cmd run dev
