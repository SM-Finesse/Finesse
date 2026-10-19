<#
=====================================================================
 Finesse 자동 배포 스크립트 (CI/CD 3단계 — 학교 PC, GitOps pull 방식)

 하는 일 (작업 스케줄러가 2분마다 실행):
   1. GitHub deploy 브랜치의 target.env 에서 "배포할 태그(TAG)"를 읽음
   2. 지금 떠 있는 태그와 같으면 → 컨테이너가 살아 있는지만 맞추고 끝
   3. 다르면 → 이미지 받기(pull) → 교체(up -d) → 정상 확인(health)
   4. 정상 확인 실패 → 직전 태그로 자동 롤백, 실패한 태그는 기록해 두고 재시도 안 함
      (target.env 가 새 태그로 바뀌면 다시 시도)

 서버 PC에는 비밀번호·토큰이 없음 (GitHub 공개 파일 + DockerHub 공개 이미지만 읽음)
 Windows PowerShell 5.1 과 PowerShell 7 둘 다에서 동작하도록 작성.

 사용:
   .\deploy.ps1            # 한 번 실행 (작업 스케줄러도 이렇게 실행)
   .\deploy.ps1 -Force     # 같은 태그라도 다시 배포
 결과: logs\deploy.log, state\ 폴더 (current_tag / previous_tag / failed_tag / last_result)
 종료 코드: 0 정상·변경 없음 / 1 설정·target 오류 / 2 이미지 받기 실패 / 3 배포 실패 후 롤백 / 4 롤백도 실패
=====================================================================
#>
[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$Root        = $PSScriptRoot
$ComposeFile = Join-Path $Root 'docker-compose.yml'
$StateDir    = Join-Path $Root 'state'
$LogDir      = Join-Path $Root 'logs'
$LogFile     = Join-Path $LogDir 'deploy.log'
New-Item -ItemType Directory -Force -Path $StateDir, $LogDir | Out-Null

# ---------------------------------------------------------------- 로그
function Write-Log {
    param([string]$Message)
    if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt 1MB)) {
        Move-Item -Force $LogFile ($LogFile + '.1')
    }
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# ---------------------------------------------------------------- 상태 파일
function Read-State {
    param([string]$Name)
    $p = Join-Path $StateDir $Name
    if (Test-Path $p) { return ((Get-Content -Raw $p) -replace '\s', '') }
    return ''
}
function Write-State {
    param([string]$Name, [string]$Value)
    $p = Join-Path $StateDir $Name
    if ([string]::IsNullOrEmpty($Value)) {
        if (Test-Path $p) { Remove-Item -Force $p }
    } else {
        Set-Content -Path $p -Value $Value -Encoding ASCII
    }
}

# ---------------------------------------------------------------- 설정 (.env → 환경변수 → 기본값 순으로 덮어씀)
function Read-EnvFile {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($raw in Get-Content $Path) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $i = $line.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $line.Substring(0, $i).Trim()
        $v = $line.Substring($i + 1).Trim().Trim('"').Trim("'")
        $map[$k] = $v
    }
    return $map
}
$DotEnv = Read-EnvFile (Join-Path $Root '.env')
function Get-Setting {
    param([string]$Name, [string]$Default)
    $fromEnv = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrEmpty($fromEnv)) { return $fromEnv }
    if ($DotEnv.ContainsKey($Name) -and $DotEnv[$Name] -ne '') { return $DotEnv[$Name] }
    return $Default
}

$TargetUrl     = Get-Setting 'TARGET_URL' 'https://raw.githubusercontent.com/SM-Finesse/Finesse/deploy/target.env'
$Profiles      = @((Get-Setting 'COMPOSE_PROFILES' 'backend,frontend').Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$BackendPort   = Get-Setting 'BACKEND_PORT' '8081'
$FrontendPort  = Get-Setting 'FRONTEND_PORT' '80'
$BackendHealth = Get-Setting 'BACKEND_HEALTH_URL' ("http://localhost:{0}/" -f $BackendPort)
$FrontendHealth= Get-Setting 'FRONTEND_HEALTH_URL' ("http://localhost:{0}/" -f $FrontendPort)
$HealthTimeout = [int](Get-Setting 'HEALTH_TIMEOUT_SEC' '180')
# CI 시험 전용: 이 태그는 정상 확인을 일부러 실패시켜 롤백 경로를 시험 (운영에서는 비워 둠)
$TestFailTag   = Get-Setting 'DEPLOY_TEST_FAIL_TAG' ''
# compose 가 .env 를 못 읽는 경우(다른 폴더에서 실행 등)를 대비해 프로필은 직접 넘김
$env:COMPOSE_PROFILES = ($Profiles -join ',')

if (-not (Test-Path (Join-Path $Root '.env'))) {
    Write-Log '[경고] .env 없음 — .env.example 을 .env 로 복사해 PC에 맞게 수정하세요 (지금은 기본값 사용)'
}

# ---------------------------------------------------------------- HTTP (5.1/7 공통으로 동작하는 저수준 방식)
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

function Get-HttpStatus {
    # 응답 코드(200, 404, 502 …)를 돌려줌. 연결 자체가 안 되면 0
    param([string]$Url)
    try {
        $req = [System.Net.WebRequest]::Create($Url)
        $req.Timeout = 5000
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $resp.Close()
        return $code
    } catch {
        $e = $_.Exception
        while ($e -and -not ($e -is [System.Net.WebException])) { $e = $e.InnerException }
        if ($e -and $e.Response) {
            $code = [int]$e.Response.StatusCode
            $e.Response.Close()
            return $code
        }
        return 0
    }
}

function Get-TargetText {
    if ($TargetUrl -match '^https?://') {
        $wc = New-Object System.Net.WebClient
        $wc.Headers.Add('Cache-Control', 'no-cache')
        $sep = '?'; if ($TargetUrl.Contains('?')) { $sep = '&' }
        return $wc.DownloadString($TargetUrl + $sep + 't=' + [DateTime]::UtcNow.Ticks)
    }
    return (Get-Content -Raw $TargetUrl)     # 시험용: 로컬 파일 경로
}

# ---------------------------------------------------------------- docker compose 실행
function Invoke-Compose {
    param([string[]]$ComposeArgs, [string]$Tag, [switch]$Quiet)
    $env:TAG = $Tag
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'          # docker 가 stderr 로 진행 상황을 써도 오류로 보지 않음
    $out = & docker compose --project-directory $Root -f $ComposeFile @ComposeArgs 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if (-not $Quiet -or $code -ne 0) {
        foreach ($l in $out) { $s = "$l".TrimEnd(); if ($s) { Write-Log ('    | ' + $s) } }
    }
    return $code
}

# ---------------------------------------------------------------- 정상 확인
function Test-Healthy {
    param([string]$Tag, [int]$TimeoutSec)
    if ($TestFailTag -and $Tag -eq $TestFailTag) {
        Write-Log ("  (시험) DEPLOY_TEST_FAIL_TAG={0} — 정상 확인을 일부러 실패 처리" -f $Tag)
        return $false
    }
    $checks = @()
    if ($Profiles -contains 'backend') {
        # 백엔드엔 /health 가 없음 → 어떤 HTTP 응답이든(404 포함) 오면 Spring 이 떠 있는 것
        $checks += @{ Name = 'backend';  Url = $BackendHealth;  Min = 100; Max = 499 }
    }
    if ($Profiles -contains 'frontend') {
        $checks += @{ Name = 'frontend'; Url = $FrontendHealth; Min = 200; Max = 399 }
    }
    if ($checks.Count -eq 0) { Write-Log '[경고] COMPOSE_PROFILES 에 backend/frontend 가 없음'; return $false }

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $done = @{}
    $last = @{}
    while ($true) {
        foreach ($c in $checks) {
            if ($done.ContainsKey($c.Name)) { continue }
            $code = Get-HttpStatus $c.Url
            $last[$c.Name] = $code
            if ($code -ge $c.Min -and $code -le $c.Max) {
                $done[$c.Name] = $true
                Write-Log ("  정상 확인: {0} ({1} → {2})" -f $c.Name, $c.Url, $code)
            }
        }
        if ($done.Count -eq $checks.Count) { return $true }
        if ((Get-Date) -gt $deadline) {
            foreach ($c in $checks) {
                if (-not $done.ContainsKey($c.Name)) {
                    Write-Log ("  응답 없음: {0} ({1}, 마지막 코드 {2}, {3}초 대기)" -f $c.Name, $c.Url, $last[$c.Name], $TimeoutSec)
                }
            }
            return $false
        }
        Start-Sleep -Seconds 3
    }
}

function Test-FrontendToBackend {
    # 참고용 점검: 프론트 Nginx → 백엔드 연결 (502/504 면 백엔드 주소·방화벽 문제)
    # 백엔드 PC 상태 때문에 프론트를 롤백하지 않도록 경고만 남김
    if (-not ($Profiles -contains 'frontend')) { return }
    $u = ("http://localhost:{0}/api/" -f $FrontendPort)
    $code = Get-HttpStatus $u
    if ($code -ge 100 -and $code -lt 500) {
        Write-Log ("  프론트→백엔드 연결 정상 ({0} → {1})" -f $u, $code)
    } else {
        Write-Log ("  [경고] 프론트→백엔드 연결 안 됨 ({0} → {1}) — BACKEND_UPSTREAM·백엔드 PC·방화벽 확인" -f $u, $code)
    }
}

# ---------------------------------------------------------------- 동시 실행 방지
$mutex = $null
try {
    $mutex = New-Object System.Threading.Mutex($false, 'FinesseDeploy')
    if (-not $mutex.WaitOne(0)) { Write-Host '다른 deploy.ps1 이 실행 중 — 건너뜀'; exit 0 }
} catch { $mutex = $null }

try {
    # ------------------------------------------------------------ 1. 목표 태그 읽기
    try {
        $text = Get-TargetText
    } catch {
        Write-Log ("[오류] target.env 를 읽지 못함 ({0}): {1}" -f $TargetUrl, $_.Exception.Message)
        exit 1
    }
    $desired = ''
    foreach ($l in ($text -split "`r?`n")) {
        if ($l -match '^\s*TAG\s*=\s*"?([^"\s#]+)"?') { $desired = $Matches[1] }
    }
    if ($desired -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        Write-Log ("[오류] target.env 의 TAG 값이 올바르지 않음: '{0}'" -f $desired)
        exit 1
    }

    $current = Read-State 'current_tag'
    $failed  = Read-State 'failed_tag'

    # ------------------------------------------------------------ 2. 변경 없음
    if (-not $Force -and $desired -eq $current) {
        # 재부팅·컨테이너 삭제 대비: 현재 버전이 떠 있도록만 맞춤 (이미 떠 있으면 아무 일 없음)
        $code = Invoke-Compose -ComposeArgs @('up', '-d') -Tag $current -Quiet
        if ($code -ne 0) { Write-Log ("[경고] 현재 버전({0}) 기동 실패 — Docker Desktop 이 켜져 있는지 확인" -f $current) }
        exit 0
    }
    if (-not $Force -and $desired -eq $failed) {
        exit 0      # 이미 실패한 태그 → 새 태그가 올 때까지 다시 시도하지 않음 (current 는 그대로 동작 중)
    }

    # ------------------------------------------------------------ 3. 배포
    $from = $current; if (-not $from) { $from = '(없음)' }
    Write-Log ("배포 시작: {0} → {1}  (프로필: {2})" -f $from, $desired, ($Profiles -join ','))

    Write-Log '  이미지 받는 중… (처음엔 1~3분)'
    # --quiet: 레이어별 진행 줄(수백 줄)을 로그에 남기지 않음 — 실패하면 오류 메시지는 그대로 나옴
    $code = Invoke-Compose -ComposeArgs @('pull', '--quiet') -Tag $desired
    if ($code -ne 0) {
        Write-Log ("[실패] 이미지 받기 실패 ({0}) — 기존 버전 그대로 유지" -f $desired)
        Write-State 'failed_tag' $desired
        Write-State 'last_result' ("pull-failed {0}" -f $desired)
        exit 2
    }

    $code = Invoke-Compose -ComposeArgs @('up', '-d', '--remove-orphans') -Tag $desired
    $ok = $false
    if ($code -eq 0) { $ok = Test-Healthy -Tag $desired -TimeoutSec $HealthTimeout }

    if ($ok) {
        if ($current -and $current -ne $desired) { Write-State 'previous_tag' $current }
        Write-State 'current_tag' $desired
        Write-State 'failed_tag' ''
        Write-State 'last_result' ("deployed {0}" -f $desired)
        Write-Log ("[성공] {0} 배포 완료" -f $desired)
        Test-FrontendToBackend
        exit 0
    }

    # ------------------------------------------------------------ 4. 실패 → 롤백
    Write-Log ("[실패] {0} 정상 확인 실패" -f $desired)
    Write-State 'failed_tag' $desired
    if (-not $current) {
        Write-Log '되돌릴 이전 버전 없음 (첫 배포) — 로그 확인 필요'
        Write-State 'last_result' ("failed-no-rollback {0}" -f $desired)
        exit 3
    }
    Write-Log ("롤백: {0} 로 되돌림" -f $current)
    $code = Invoke-Compose -ComposeArgs @('up', '-d', '--remove-orphans') -Tag $current
    if ($code -eq 0 -and (Test-Healthy -Tag $current -TimeoutSec $HealthTimeout)) {
        Write-Log ("[롤백 성공] {0} 로 복구됨 ({1} 은 다음 새 태그가 올 때까지 다시 시도 안 함)" -f $current, $desired)
        Write-State 'last_result' ("rolled-back {0} -> {1}" -f $desired, $current)
        exit 3
    }
    Write-Log '[롤백 실패] 서비스가 내려가 있을 수 있음 — 바로 확인 필요 (docker compose ps / logs)'
    Write-State 'last_result' ("rollback-failed {0}" -f $current)
    exit 4
}
finally {
    if ($mutex) { try { $mutex.ReleaseMutex() } catch { } ; $mutex.Dispose() }
}
