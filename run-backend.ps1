$ErrorActionPreference = "Stop"
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "  ZENVEGO BACKEND ENV LOADER + RUNNER" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# Step 0: Kill any zombie processes holding PORT
$portEnv = if ([string]::IsNullOrWhiteSpace($env:PORT)) { "8080" } else { $env:PORT }
$holdingPid = netstat -ano | Select-String "LISTENING" | ForEach-Object {
    if ($_ -match ":$portEnv\s+\S+\s+LISTENING\s+(\d+)") { [int]$matches[1] }
} | Select-Object -First 1
if ($holdingPid) {
    Write-Host "[0/4] Port $portEnv held by PID $holdingPid -- killing zombie..." -ForegroundColor Yellow
    Stop-Process -Id $holdingPid -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 1500
    Write-Host "       Killed." -ForegroundColor Green
} else {
    Write-Host "[0/4] Port $portEnv is free." -ForegroundColor Green
}

if (-not (Test-Path ".env")) {
    Write-Host "[ERROR] .env file not found in $PWD" -ForegroundColor Red
    exit 1
}

# Step 1: Load .env
Write-Host "[1/4] Loading secrets from .env ..." -ForegroundColor Gray
Get-Content .env | ForEach-Object {
    if ($_ -match '^([^#=][^=]*)=(.*)$') {
        $name = $matches[1].Trim()
        $val  = $matches[2].Trim()
        [Environment]::SetEnvironmentVariable($name, $val, 'Process')
    }
}

$port = if ([string]::IsNullOrWhiteSpace($env:PORT)) { 8080 } else { [int]$env:PORT }
Write-Host "       SMTP_USER  = $env:EMAIL_USER" -ForegroundColor Gray
$uriLen = $env:MONGODB_URI.Length
Write-Host "       MONGODB_URI = set ($uriLen chars)" -ForegroundColor Gray
Write-Host "       HTTP PORT  = $port" -ForegroundColor Gray

# Validate required fields
$needFix = @()
if ([string]::IsNullOrWhiteSpace($env:EMAIL_USER) -or $env:EMAIL_USER -like '*your-email*') {
    $needFix += "EMAIL_USER not set (must be your real Gmail)"
}
$pwLen = $env:EMAIL_PASS.Length
if ($pwLen -lt 12 -or $env:EMAIL_PASS -match '\s') {
    $needFix += "EMAIL_PASS too short or contains spaces (got $pwLen chars, need 16+)"
}
if ($env:MONGODB_URI -match '<db_password>' -or $uriLen -lt 30) {
    Write-Host "[WARN] MongoDB is not configured. OTP email will work, but user profiles will not persist." -ForegroundColor Yellow
}

if ($needFix.Count -gt 0) {
    Write-Host ""
    Write-Host "[ACTION REQUIRED BEFORE STARTING]" -ForegroundColor Yellow
    Write-Host "Open file:  d:\email-otp-bot\.env   in Notepad and fix these:" -ForegroundColor Yellow
    $needFix | ForEach-Object { Write-Host "   [X] $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "MongoDB Atlas Password Setup (FREE):" -ForegroundColor Cyan
    Write-Host "  1. Go to https://cloud.mongodb.com --> Security --> Database Access" -ForegroundColor Cyan
    Write-Host "  2. Edit user:  vishwabaddam_db_user  --> Edit Password" -ForegroundColor Cyan
    Write-Host "  3. Set password to:   Zenvego2026   (letters + numbers only, no special symbols)" -ForegroundColor Cyan
    Write-Host "  4. Click the green [Update User] button." -ForegroundColor Cyan
    Write-Host "  5. In d:\email-otp-bot\.env, go to line 12." -ForegroundColor Cyan
    Write-Host "     Find the literal text  <db_password>  and DELETE those 12 characters." -ForegroundColor Cyan
    Write-Host "     In its place, type the password you just set:  Zenvego2026" -ForegroundColor Cyan
    Write-Host "     Result line 12: mongodb+srv://vishwabaddam_db_user:Zenvego2026@zenevgo.xwebrgt.mongodb.net/zenvego?retryWrites=true&w=majority" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  ALSO REQUIRED: Atlas --> Security --> Network Access --> ADD IP ADDRESS" -ForegroundColor Cyan
    Write-Host "     Click [ALLOW ACCESS FROM ANYWHERE] --> it adds 0.0.0.0/0 --> confirm." -ForegroundColor Cyan
    Write-Host ""
    exit 1
}

# Step 2: Maven build if needed
if (-not (Test-Path "target\classes")) {
    Write-Host ""
    Write-Host "[2/4] target\classes missing. Running Maven build ..." -ForegroundColor Gray
    try {
        $env:JAVA_HOME = (Get-Command java.exe).Source | Split-Path | Split-Path
    } catch {}
    & "$PWD\maven\apache-maven-3.9.6\bin\mvn.cmd" -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Maven build failed (exit $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    Write-Host "       Maven build OK." -ForegroundColor Green
} else {
    Write-Host "[2/4] target\classes found. Skipping build." -ForegroundColor Gray
    Write-Host "       (Delete 'target' folder to force rebuild)" -ForegroundColor DarkGray
}

# Step 3: Banner
Write-Host ""
Write-Host "[3/4] Starting OTPServer..." -ForegroundColor Gray
Write-Host "       Health URL   : http://localhost:$port/health" -ForegroundColor DarkGray
Write-Host "       Allowed from : $env:ALLOWED_ORIGINS" -ForegroundColor DarkGray
Write-Host "       Press Ctrl+C at any time to stop." -ForegroundColor DarkGray
Write-Host ""

# Step 4: Run server
$mongoJarDir = "$PWD\target\lib\*"
& java -cp "target\classes;$mongoJarDir" com.emailbot.OTPServer
