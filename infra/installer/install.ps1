<#
=====================================================================
 Finesse 통합 운영 서버 간편 설치기 (v0.1)

 하는 일: 학교 PC 한 대를 "통합 운영 서버"로 만든다
   사이트(CD) + 상태 화면(Uptime Kuma) + 자동 QA + heartbeat + Cloudflare Tunnel
 근거: 2026-10-26 301B-24 → 301B-12 이전 기록 (프로젝트 문서 finesse-12-migration-2026-10-26.md)

 사용 (관리자 PowerShell, 또는 같은 폴더의 install.bat / check.bat 더블클릭)
   .\install.ps1                     # 설치 (이미 된 단계는 건너뜀 → 몇 번 실행해도 안전)
   .\install.ps1 -CheckOnly          # 점검만 (아무것도 바꾸지 않고 단계별 상태만 표시)
   .\install.ps1 -BackupDir D:\finesse-backup
   .\install.ps1 -GitName 301B-00 -GitEmail you@example.com
   .\install.ps1 -SkipTunnel         # 터널은 나중에 (이전 서버가 아직 켜져 있을 때)

 백업 폴더(기본: 이 폴더의 backup\)에 필요한 파일 — 이전 서버에서 만든 것
   finesse-local.zip        C:\finesse 에 덮어쓸 .env·state·logs·ops (최상위 폴더 없이 infra\...)
   finesse-ops.zip          finesse-ops\ (monitoring compose, heartbeat)
   finesse-qa.zip           finesse-qa\  (run-qa.bat, Postman 컬렉션, ui)
   uptime-kuma-data.tar.gz  Kuma 볼륨 데이터 (./kuma.db ...)
   task-heartbeat.xml       작업 "Finesse Heartbeat" (Ping URL 포함 — 비밀값)
   cloudflared-token        터널 토큰 (비밀값)
 비밀값은 이 스크립트에 넣지 않는다. 백업 폴더는 레포에 올리지 않는다(.gitignore).

 사람이 해야 하는 것: BIOS 가상화(꺼져 있을 때), WSL 설치 후 재부팅 1회,
   Docker Desktop 설치(All-users)·첫 실행·"로그인 시 자동 시작" 체크,
   Healthchecks.io 체크 이름, 팀 공지
 Windows PowerShell 5.1 기준. 이 파일은 UTF-8(BOM)으로 저장해야 한글이 깨지지 않는다.
=====================================================================
#>
[CmdletBinding()]
param(
    [string]$BackupDir = '',
    [switch]$CheckOnly,
    [string]$RepoUrl  = 'https://github.com/SM-Finesse/Finesse',
    [string]$RepoDir  = 'C:\finesse',
    [string]$OpsDir   = 'C:\finesse-ops',
    [string]$QaDir    = 'C:\finesse-qa',
    [string]$GitName  = '',
    [string]$GitEmail = '',
    [string]$WslMemory = '8GB',
    [string]$LanCidr  = '192.168.0.0/24',
    [string]$PublicUrl = 'https://finesse-lab.com/',
    [switch]$SkipAutoLogon,
    [switch]$SkipTunnel,
    [switch]$Yes
)

$ErrorActionPreference = 'Continue'   # 네이티브 명령의 stderr 를 오류로 보지 않음 — 종료 코드로 판단
Set-StrictMode -Version 2

$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $BackupDir) { $BackupDir = Join-Path $ScriptDir 'backup' }

$KumaVolume  = 'finesse-monitoring_kuma-data'   # monitoring compose 의 name: finesse-monitoring + 볼륨 kuma-data
$DockerExe   = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$NewmanVer   = '6.2.2'
$HtmlextraVer = '1.23.1'

$LogDir = Join-Path $ScriptDir 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Stamp = Get-Date -Format 'yyyyMMdd_HHmm'
try { Start-Transcript -Path (Join-Path $LogDir ("install_{0}.log" -f $Stamp)) | Out-Null } catch { }

# ---------------------------------------------------------------- 출력·결과 기록
$Results = New-Object System.Collections.Generic.List[object]
$Manual  = New-Object System.Collections.Generic.List[string]

function Add-Result {
    param([string]$Step, [string]$Status, [string]$Detail = '')
    $Results.Add([pscustomobject]@{ 단계 = $Step; 상태 = $Status; 내용 = $Detail })
    $color = 'Gray'
    switch ($Status) {
        '완료'    { $color = 'Green' }
        '이미 됨' { $color = 'Green' }
        '할 일'   { $color = 'Yellow' }
        '수동'    { $color = 'Yellow' }
        '경고'    { $color = 'Yellow' }
        '건너뜀'  { $color = 'DarkGray' }
        '정보'    { $color = 'DarkGray' }
        '확인 생략' { $color = 'DarkGray' }
        '실패'    { $color = 'Red' }
    }
    $line = "  [{0}] {1}" -f $Status, $Step
    if ($Detail) { $line += " — $Detail" }
    Write-Host $line -ForegroundColor $color
}

function Write-Section { param([string]$Title) Write-Host ''; Write-Host "== $Title ==" -ForegroundColor Cyan }

function Stop-Install {
    param([string]$Message)
    Write-Host ''
    Write-Host "[중단] $Message" -ForegroundColor Red
    Write-Host '해결한 뒤 다시 실행하면 이미 된 단계는 건너뛰고 이어서 진행합니다.' -ForegroundColor Red
    Show-Summary
    try { Stop-Transcript | Out-Null } catch { }
    exit 2
}

# 한 단계: Test 가 $true 면 건너뜀, $null 이면 "확인 불가(매번 적용)", $false 면 Do 실행 후 다시 Test
function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Test,
        [scriptblock]$Do,
        [switch]$Critical
    )
    $state = $null
    try { $state = & $Test } catch { $state = $false }
    if ($state -eq $true) { Add-Result $Name '이미 됨'; return $true }
    if ($CheckOnly) {
        if ($null -eq $state) { Add-Result $Name '확인 생략' '확인 대신 설치 때마다 적용하는 설정' }
        else { Add-Result $Name '할 일' }
        return $false
    }
    try { & $Do } catch { Add-Result $Name '실패' $_.Exception.Message; if ($Critical) { Stop-Install "$Name 실패" }; return $false }
    $after = $null
    try { $after = & $Test } catch { $after = $false }
    if ($after -eq $true -or ($null -eq $state -and $null -eq $after)) { Add-Result $Name '완료'; return $true }
    Add-Result $Name '실패' '실행 후 확인에서 아직 안 됨'
    if ($Critical) { Stop-Install "$Name 실패" }
    return $false
}

function Confirm-Ask {
    param([string]$Question)
    if ($Yes) { return $true }
    $a = Read-Host "$Question (y/N)"
    return ($a -match '^(y|yes|ㅇ|예|네)$')
}

# ---------------------------------------------------------------- 도구
function Update-PathEnv {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}
function Test-Cmd { param([string]$Name) return [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

function Invoke-Native {
    # 네이티브 명령 실행 → @{ Code; Out }
    param([string]$File, [string[]]$ArgList = @())
    $out = & $File @ArgList 2>&1
    return @{ Code = $LASTEXITCODE; Out = (($out | ForEach-Object { "$_" }) -join "`n") }
}

function Install-Winget {
    param([string]$Id)
    $r = Invoke-Native 'winget' @('install', '-e', '--id', $Id, '--accept-package-agreements', '--accept-source-agreements', '--silent')
    Update-PathEnv
    if ($r.Code -ne 0) { throw "winget install $Id 종료 코드 $($r.Code)" }
}

function Get-HttpStatus {
    # 응답 코드(200, 404 …). 연결 자체가 안 되면 0 — deploy.ps1 과 같은 방식(5.1/7 공통)
    param([string]$Url)
    try {
        $req = [System.Net.WebRequest]::Create($Url)
        $req.Timeout = 10000
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $resp.Close()
        return $code
    } catch {
        $e = $_.Exception
        while ($e -and -not ($e -is [System.Net.WebException])) { $e = $e.InnerException }
        if ($e -and $e.Response) { $code = [int]$e.Response.StatusCode; $e.Response.Close(); return $code }
        return 0
    }
}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }

function Test-DockerEngine {
    if (-not (Test-Cmd 'docker')) { return $false }
    $r = Invoke-Native 'docker' @('info', '--format', '{{.ServerVersion}}')
    return ($r.Code -eq 0 -and $r.Out.Trim() -ne '')
}

function Wait-DockerEngine {
    param([int]$TimeoutSec = 240)
    if (Test-DockerEngine) { return $true }
    if ((Test-Path $DockerExe) -and -not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
        Write-Host '  Docker Desktop 을 켜는 중… (처음엔 1~3분)'
        Start-Process -FilePath $DockerExe | Out-Null
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        Update-PathEnv
        if (Test-DockerEngine) { return $true }
    }
    return $false
}

function Test-ContainerRunning {
    param([string]$Name)
    if (-not (Test-Cmd 'docker')) { return $false }
    $r = Invoke-Native 'docker' @('ps', '--filter', "name=^$Name$", '--format', '{{.Names}}')
    return ($r.Code -eq 0 -and $r.Out.Trim() -eq $Name)
}

function Test-Task { param([string]$Name) return [bool](Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue) }

function Find-BackupDir {
    # 백업 폴더 안에 같은 이름 폴더가 한 번 더 있는 경우(zip 을 풀면 흔함)도 찾아 줌
    param([string]$Dir)
    if (Test-Path (Join-Path $Dir 'finesse-local.zip')) { return $Dir }
    if (Test-Path $Dir) {
        foreach ($sub in Get-ChildItem -Path $Dir -Directory -ErrorAction SilentlyContinue) {
            if (Test-Path (Join-Path $sub.FullName 'finesse-local.zip')) { return $sub.FullName }
        }
    }
    return $Dir
}

function Show-Summary {
    Write-Host ''
    Write-Host '== 결과 요약 ==' -ForegroundColor Cyan
    $Results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    if ($Manual.Count -gt 0) {
        Write-Host '== 사람이 할 일 ==' -ForegroundColor Yellow
        $i = 1
        foreach ($m in $Manual) { Write-Host ("  {0}. {1}" -f $i, $m) -ForegroundColor Yellow; $i++ }
    }
}

# =====================================================================
Write-Host "Finesse 통합 운영 서버 설치기 — $env:COMPUTERNAME / $env:USERDOMAIN\$env:USERNAME" -ForegroundColor Cyan
if ($CheckOnly) { Write-Host '점검 모드: 아무것도 바꾸지 않습니다.' -ForegroundColor Cyan }

# ---------------------------------------------------------------- 0. 사전 점검
Write-Section '0. 사전 점검'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) { Add-Result '관리자 권한' '이미 됨' }
else {
    Add-Result '관리자 권한' '실패' '관리자 PowerShell 에서 실행하거나 install.bat 을 쓰세요'
    if (-not $CheckOnly) { Stop-Install '관리자 권한이 필요합니다' }
}

$BackupDir = Find-BackupDir $BackupDir
$need = @('finesse-local.zip', 'finesse-ops.zip', 'finesse-qa.zip', 'uptime-kuma-data.tar.gz', 'task-heartbeat.xml', 'cloudflared-token')
$missing = @($need | Where-Object { -not (Test-Path (Join-Path $BackupDir $_)) })
if ($missing.Count -eq 0) { Add-Result '백업 파일 6개' '이미 됨' $BackupDir }
else {
    Add-Result '백업 파일' '실패' ("없음: {0} (폴더: {1})" -f ($missing -join ', '), $BackupDir)
    if (-not $CheckOnly) { Stop-Install '백업 폴더를 확인하세요 (-BackupDir 로 지정 가능)' }
}

# 가상화: VirtualizationFirmwareEnabled 는 하이퍼바이저가 이미 돌면 False 로 나와서 그것만 보고 판단하면 안 됨 (10/26 12번에서 오판)
$cs = Get-CimInstance Win32_ComputerSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$virt = '확인 못 함'
if ($cs.HypervisorPresent) { $virt = '하이퍼바이저 동작 중' }
elseif ($cpu.VirtualizationFirmwareEnabled) { $virt = 'BIOS 가상화 켜짐' }
else {
    $cctk = 'C:\Program Files (x86)\Dell\Command Configure\X86_64\cctk.exe'
    if (Test-Path $cctk) {
        $r = Invoke-Native $cctk @('--Virtualization')
        if ($r.Out -match 'Enabled') { $virt = 'BIOS 가상화 켜짐 (Dell cctk)' } else { $virt = '' }
    }
}
if ($virt) { Add-Result '가상화' '이미 됨' $virt }
else {
    Add-Result '가상화' '경고' 'BIOS 가상화가 꺼져 있을 수 있음'
    $Manual.Add('Docker 가 "가상화" 오류를 내면 BIOS 에서 Intel Virtualization Technology 를 켜세요 (Dell: 부팅 중 F2, 또는 Dell Command Configure 의 cctk --Virtualization=Enabled)')
}
Add-Result 'PC 정보' '정보' ("{0}, RAM {1}GB, C: 여유 {2}GB" -f (Get-CimInstance Win32_OperatingSystem).Caption, [math]::Round($cs.TotalPhysicalMemory / 1GB), [math]::Round((Get-PSDrive C).Free / 1GB))

Invoke-Step -Name '실행 정책 RemoteSigned (npm·ps1 실행용)' -Test {
    (Get-ExecutionPolicy -Scope LocalMachine) -in @('RemoteSigned', 'Unrestricted', 'Bypass')
} -Do { Set-ExecutionPolicy RemoteSigned -Scope LocalMachine -Force } | Out-Null

# ---------------------------------------------------------------- 1. 프로그램
Write-Section '1. 프로그램'
Update-PathEnv

if (-not (Test-Cmd 'winget')) {
    Add-Result 'winget' '실패' 'Microsoft Store 의 "앱 설치 관리자"를 설치/업데이트하세요'
    if (-not $CheckOnly) { Stop-Install 'winget 이 없습니다' }
}

Invoke-Step -Name 'Git' -Test { Test-Cmd 'git' } -Do { Install-Winget 'Git.Git' } -Critical | Out-Null
Invoke-Step -Name 'Node.js LTS' -Test { Test-Cmd 'node' } -Do { Install-Winget 'OpenJS.NodeJS.LTS' } -Critical | Out-Null
Invoke-Step -Name 'cloudflared' -Test { Test-Cmd 'cloudflared' } -Do { Install-Winget 'Cloudflare.cloudflared' } | Out-Null

$wslOk = Invoke-Step -Name 'WSL 2' -Test {
    $f = Get-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -ErrorAction SilentlyContinue
    $r = Invoke-Native 'wsl.exe' @('--status')
    ($f -and $f.State -eq 'Enabled' -and $r.Code -eq 0)
} -Do {
    $r = Invoke-Native 'wsl.exe' @('--install', '--no-distribution')
    if ($r.Code -ne 0) {
        # 대안: Windows 기능을 직접 켬
        Invoke-Native 'dism.exe' @('/online', '/enable-feature', '/featurename:Microsoft-Windows-Subsystem-Linux', '/all', '/norestart') | Out-Null
        Invoke-Native 'dism.exe' @('/online', '/enable-feature', '/featurename:VirtualMachinePlatform', '/all', '/norestart') | Out-Null
    }
    Stop-Install 'WSL 을 설치했습니다. 재부팅(Restart-Computer) 후 설치기를 다시 실행하세요'
}

Invoke-Step -Name 'Docker Desktop' -Test { Test-Path $DockerExe } -Do {
    $Manual.Add('Docker Desktop 설치: https://www.docker.com/products/docker-desktop/ → Windows AMD64 → "All-users installation" + WSL 2 → 첫 실행해서 약관 동의')
    Stop-Install 'Docker Desktop 을 설치한 뒤 다시 실행하세요 (설치 방식 선택이 필요해서 자동으로 하지 않음)'
} | Out-Null

# ---------------------------------------------------------------- 2. 기본 설정
Write-Section '2. 기본 설정'

if ($GitName -or $GitEmail) {
    Invoke-Step -Name 'git 사용자' -Test {
        $n = (Invoke-Native 'git' @('config', '--global', 'user.name')).Out.Trim()
        $m = (Invoke-Native 'git' @('config', '--global', 'user.email')).Out.Trim()
        ((-not $GitName -or $n -eq $GitName) -and (-not $GitEmail -or $m -eq $GitEmail))
    } -Do {
        if ($GitName)  { Invoke-Native 'git' @('config', '--global', 'user.name', $GitName) | Out-Null }
        if ($GitEmail) { Invoke-Native 'git' @('config', '--global', 'user.email', $GitEmail) | Out-Null }
    } | Out-Null
} else { Add-Result 'git 사용자' '건너뜀' '-GitName / -GitEmail 을 주면 설정 (배포에는 필요 없음)' }

Invoke-Step -Name '절전 끄기 (전원 연결 시)' -Test { $null } -Do {
    Invoke-Native 'powercfg' @('/change', 'standby-timeout-ac', '0') | Out-Null
    Invoke-Native 'powercfg' @('/change', 'hibernate-timeout-ac', '0') | Out-Null
} | Out-Null

$wl = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
if ($SkipAutoLogon) { Add-Result '자동 로그온' '건너뜀' '-SkipAutoLogon' }
else {
    Invoke-Step -Name '자동 로그온 (비밀번호 없는 계정)' -Test {
        $v = Get-ItemProperty $wl
        ($v.AutoAdminLogon -eq '1' -and $v.DefaultUserName -eq $env:USERNAME -and $v.DefaultDomainName -eq $env:COMPUTERNAME)
    } -Do {
        if (-not (Confirm-Ask "계정 $env:USERNAME 이 비밀번호 없이 로그인됩니까? (Win+L 로 확인 가능)")) {
            $Manual.Add('자동 로그온: 비밀번호가 있는 계정이면 Sysinternals Autologon 으로 설정')
            throw '비밀번호 없는 계정이 아니라서 건너뜀'
        }
        Set-ItemProperty $wl -Name AutoAdminLogon -Value '1'
        Set-ItemProperty $wl -Name DefaultUserName -Value $env:USERNAME
        Set-ItemProperty $wl -Name DefaultDomainName -Value $env:COMPUTERNAME
        Set-ItemProperty $wl -Name DefaultPassword -Value ''
    } | Out-Null
}

$wslcfg = Join-Path $env:USERPROFILE '.wslconfig'
Invoke-Step -Name "WSL 메모리 $WslMemory" -Test {
    (Test-Path $wslcfg) -and ((Get-Content $wslcfg -Raw) -match ("memory\s*=\s*" + [regex]::Escape($WslMemory)))
} -Do {
    Set-Content -Path $wslcfg -Value ("[wsl2]`r`nmemory=$WslMemory") -Encoding ASCII
    $Manual.Add('WSL 메모리 설정은 다음 재부팅(또는 wsl --shutdown 후 Docker 재시작) 때 적용됩니다')
} | Out-Null

Invoke-Step -Name '방화벽 80 (학교망)' -Test { [bool](Get-NetFirewallRule -DisplayName 'Finesse HTTP 80' -ErrorAction SilentlyContinue) } -Do {
    New-NetFirewallRule -DisplayName 'Finesse HTTP 80' -Direction Inbound -Protocol TCP -LocalPort 80 -RemoteAddress $LanCidr -Action Allow | Out-Null
} | Out-Null
Invoke-Step -Name '방화벽 3001 (학교망)' -Test { [bool](Get-NetFirewallRule -DisplayName 'Finesse Kuma 3001' -ErrorAction SilentlyContinue) } -Do {
    New-NetFirewallRule -DisplayName 'Finesse Kuma 3001' -Direction Inbound -Protocol TCP -LocalPort 3001 -RemoteAddress $LanCidr -Action Allow | Out-Null
} | Out-Null

# Docker 자동 시작은 Docker Desktop 화면 설정 — 값만 읽어서 알려 줌(설정 파일 형식이 버전마다 달라 직접 고치지 않음)
$dsFile = Join-Path $env:APPDATA 'Docker\settings-store.json'
$autoStart = $null
if (Test-Path $dsFile) { try { $autoStart = (Get-Content $dsFile -Raw | ConvertFrom-Json).AutoStart } catch { } }
if ($autoStart -eq $true) { Add-Result 'Docker 로그인 시 자동 시작' '이미 됨' }
else {
    Add-Result 'Docker 로그인 시 자동 시작' '수동' '확인 못 함 또는 꺼짐'
    $Manual.Add('Docker Desktop → Settings → General → "Start Docker Desktop when you sign in" 체크 → Apply')
}

# ---------------------------------------------------------------- 3. 파일 배치
Write-Section '3. 레포·백업 배치'

Invoke-Step -Name "레포 clone → $RepoDir" -Test { Test-Path (Join-Path $RepoDir '.git') } -Do {
    $r = Invoke-Native 'git' @('clone', $RepoUrl, $RepoDir)
    if ($r.Code -ne 0) { throw $r.Out }
} -Critical | Out-Null

Invoke-Step -Name 'finesse-local.zip → 레포 (.env·state·ops)' -Test { Test-Path (Join-Path $RepoDir 'infra\deploy\.env') } -Do {
    Expand-Archive -Path (Join-Path $BackupDir 'finesse-local.zip') -DestinationPath $RepoDir -Force
} -Critical | Out-Null

Invoke-Step -Name "finesse-ops.zip → $OpsDir" -Test { Test-Path (Join-Path $OpsDir 'monitoring\docker-compose.yml') } -Do {
    Expand-Archive -Path (Join-Path $BackupDir 'finesse-ops.zip') -DestinationPath (Split-Path $OpsDir -Parent) -Force
} -Critical | Out-Null

Invoke-Step -Name "finesse-qa.zip → $QaDir" -Test { Test-Path (Join-Path $QaDir 'run-qa.bat') } -Do {
    Expand-Archive -Path (Join-Path $BackupDir 'finesse-qa.zip') -DestinationPath (Split-Path $QaDir -Parent) -Force
} | Out-Null

if (-not $CheckOnly) {
    # 인터넷에서 받은 zip 을 풀면 .ps1 이 RemoteSigned 에 막힐 수 있음
    foreach ($d in @($RepoDir, $OpsDir, $QaDir)) {
        if (Test-Path $d) { Get-ChildItem -Path $d -Recurse -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue }
    }
    Add-Result '다운로드 차단 표시 해제 (Unblock-File)' '완료'
}

# ---------------------------------------------------------------- 4. 사이트 + CD
Write-Section '4. 사이트 + CD'

if ($CheckOnly) {
    if (Test-DockerEngine) { Add-Result 'Docker 엔진' '이미 됨' } else { Add-Result 'Docker 엔진' '할 일' 'Docker Desktop 실행' }
} else {
    if (Wait-DockerEngine) { Add-Result 'Docker 엔진' '이미 됨' }
    else { Stop-Install 'Docker 엔진이 켜지지 않습니다. Docker Desktop 을 직접 켜고 Engine running 을 확인하세요' }
}

$deployPs1 = Join-Path $RepoDir 'infra\deploy\deploy.ps1'
Invoke-Step -Name '사이트 기동 (deploy.ps1 — deploy 브랜치 태그)' -Test {
    (Test-ContainerRunning 'finesse-frontend-1') -and (Test-ContainerRunning 'finesse-backend-1')
} -Do {
    # 백업의 state\current_tag 와 deploy 브랜치 target 이 같으면 up -d 만, 다르면 받기 → 교체 → 정상 확인
    $r = Invoke-Native 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $deployPs1)
    if ($r.Code -ne 0) { throw "deploy.ps1 종료 코드 $($r.Code) — $RepoDir\infra\deploy\logs\deploy.log 확인" }
    Start-Sleep -Seconds 5
} -Critical | Out-Null

Invoke-Step -Name '배포 작업 Finesse-Deploy (2분마다)' -Test { Test-Task 'Finesse-Deploy' } -Do {
    $r = Invoke-Native 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $RepoDir 'infra\deploy\install-task.ps1'))
    if ($r.Code -ne 0) { throw $r.Out }
} | Out-Null

# ---------------------------------------------------------------- 5. 모니터링
Write-Section '5. 모니터링 (Uptime Kuma + autoheal)'

$monCompose = Join-Path $OpsDir 'monitoring\docker-compose.yml'
Invoke-Step -Name 'Kuma 데이터 복원 + 기동' -Test {
    (Test-ContainerRunning 'uptime-kuma') -and (Test-ContainerRunning 'autoheal')
} -Do {
    $vol = Invoke-Native 'docker' @('volume', 'inspect', $KumaVolume)
    if ($vol.Code -ne 0) {
        # 볼륨이 없을 때만 복원 — 이미 있는 Kuma 데이터는 절대 덮어쓰지 않음
        # 컨테이너를 먼저 켜면 빈 DB 가 생기므로: 만들기(create) → 복원 → 시작
        $r = Invoke-Native 'docker' @('compose', '-f', $monCompose, 'create')
        if ($r.Code -ne 0) { throw $r.Out }
        $r = Invoke-Native 'docker' @('run', '--rm', '-v', "${KumaVolume}:/data", '-v', "${BackupDir}:/backup:ro", 'alpine',
            'sh', '-c', 'tar -xzf /backup/uptime-kuma-data.tar.gz -C /data && ls /data')
        if ($r.Code -ne 0 -or $r.Out -notmatch 'kuma\.db') { throw "복원 실패: $($r.Out)" }
        Write-Host '  Kuma 데이터 복원함 (kuma.db)'
    } else {
        Write-Host "  볼륨 $KumaVolume 이 이미 있어 복원하지 않고 기동만 함"
    }
    $r = Invoke-Native 'docker' @('compose', '-f', $monCompose, 'up', '-d')
    if ($r.Code -ne 0) { throw $r.Out }
    Start-Sleep -Seconds 5
} | Out-Null

# ---------------------------------------------------------------- 6. 자동 QA + heartbeat
Write-Section '6. 자동 QA + heartbeat'

Invoke-Step -Name "Newman $NewmanVer + htmlextra $HtmlextraVer" -Test {
    if (-not (Test-Cmd 'newman.cmd')) { return $false }
    $v = (Invoke-Native 'newman.cmd' @('-v')).Out.Trim()
    $h = (Invoke-Native 'npm.cmd' @('ls', '-g', 'newman-reporter-htmlextra', '--depth=0')).Out
    ($v -eq $NewmanVer -and $h -match [regex]::Escape($HtmlextraVer))
} -Do {
    $r = Invoke-Native 'npm.cmd' @('install', '-g', "newman@$NewmanVer", "newman-reporter-htmlextra@$HtmlextraVer")
    Update-PathEnv
    if ($r.Code -ne 0) { throw $r.Out }
} | Out-Null

$uiDir = Join-Path $QaDir 'ui'
Invoke-Step -Name 'QA 화면 테스트 패키지 (ui, package-lock 기준)' -Test { Test-Path (Join-Path $uiDir 'node_modules\@playwright\test') } -Do {
    Push-Location $uiDir
    try {
        # npm audit 경고가 나와도 "audit fix --force" 는 하지 않음 (Playwright 버전이 바뀜)
        $r = Invoke-Native 'npm.cmd' @('ci')
        if ($r.Code -ne 0) { $r = Invoke-Native 'npm.cmd' @('install') }
        if ($r.Code -ne 0) { throw $r.Out }
    } finally { Pop-Location }
} | Out-Null

Invoke-Step -Name 'Playwright 크롬 브라우저' -Test {
    [bool](Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'ms-playwright') -Filter 'chromium-*' -Directory -ErrorAction SilentlyContinue)
} -Do {
    Push-Location $uiDir
    try { $r = Invoke-Native 'npx.cmd' @('playwright', 'install', 'chromium'); if ($r.Code -ne 0) { throw $r.Out } } finally { Pop-Location }
} | Out-Null

Invoke-Step -Name '자동 QA 작업 Finesse QA (매일 09:00)' -Test { Test-Task 'Finesse QA' } -Do {
    # 24번 XML 대신 새로 등록: 배터리 조건 끔, 놓친 실행은 켜진 뒤 실행, 작업 폴더 지정
    $a = New-ScheduledTaskAction -Execute (Join-Path $QaDir 'run-qa.bat') -WorkingDirectory $QaDir
    $t = New-ScheduledTaskTrigger -Daily -At 9:00
    $p = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    $s = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
    Register-ScheduledTask -TaskName 'Finesse QA' -Action $a -Trigger $t -Principal $p -Settings $s `
        -Description 'Finesse 자동 QA: 매일 09:00 run-qa.bat (Newman+Playwright, 결과는 Kuma Push)' | Out-Null
} | Out-Null

Invoke-Step -Name 'heartbeat 작업 Finesse Heartbeat (SYSTEM, 1분)' -Test { Test-Task 'Finesse Heartbeat' } -Do {
    # XML 에 PC 고유값이 없음(SYSTEM = S-1-5-18) → 그대로 가져옴. Ping URL 은 화면에 띄우지 않음
    Register-ScheduledTask -TaskName 'Finesse Heartbeat' -Xml (Get-Content (Join-Path $BackupDir 'task-heartbeat.xml') -Raw) | Out-Null
} | Out-Null

# ---------------------------------------------------------------- 7. 터널
Write-Section '7. Cloudflare Tunnel'

if ($SkipTunnel) { Add-Result '터널 서비스' '건너뜀' '-SkipTunnel' }
else {
    Invoke-Step -Name '터널 서비스 cloudflared' -Test { [bool](Get-Service -Name 'cloudflared' -ErrorAction SilentlyContinue) } -Do {
        if (-not (Confirm-Ask '이전 서버의 cloudflared 가 꺼져 있습니까? (두 PC 에서 켜지면 접속이 나뉨)')) {
            $Manual.Add('터널: 이전 서버의 cloudflared 를 끈 뒤 설치기를 다시 실행 (또는 -SkipTunnel)')
            throw '확인이 없어 건너뜀'
        }
        $token = (Get-Content (Join-Path $BackupDir 'cloudflared-token') -Raw).Trim()
        $r = Invoke-Native 'cloudflared' @('service', 'install', $token)
        $token = $null
        if ($r.Code -ne 0) { throw "cloudflared service install 종료 코드 $($r.Code)" }
        Start-Sleep -Seconds 10
    } | Out-Null
}

# ---------------------------------------------------------------- 8. 통합 관리 창 설정
Write-Section '8. 통합 관리 창(콘솔) 설정'

$consoleCfg = Join-Path $RepoDir 'infra\ops\console.config.json'
if (Test-Path $consoleCfg) {
    Invoke-Step -Name '콘솔 이름을 이 PC 로' -Test {
        (Get-Content $consoleCfg -Raw -Encoding UTF8) -match ('"Name"\s*:\s*"' + [regex]::Escape($env:COMPUTERNAME))
    } -Do {
        $txt = Get-Content $consoleCfg -Raw -Encoding UTF8
        $txt = [regex]::Replace($txt, '"Name"\s*:\s*"[^"]*"', ('"Name": "{0} (운영)"' -f $env:COMPUTERNAME))
        Set-Content -Path $consoleCfg -Value $txt -Encoding UTF8 -NoNewline
    } | Out-Null
} else { Add-Result '콘솔 설정' '건너뜀' 'console.config.json 없음' }

# ---------------------------------------------------------------- 9. 최종 확인
Write-Section '9. 최종 확인'

foreach ($c in @('finesse-frontend-1', 'finesse-backend-1', 'uptime-kuma', 'autoheal')) {
    if (Test-Cmd 'docker') {
        $r = Invoke-Native 'docker' @('ps', '--filter', "name=^$c$", '--format', '{{.Status}}')
        $st = $r.Out.Trim()
        if (-not $st) { Add-Result "컨테이너 $c" '실패' '실행 중 아님' }
        elseif ($st -match 'unhealthy') { Add-Result "컨테이너 $c" '실패' $st }
        elseif ($st -match 'starting') { Add-Result "컨테이너 $c" '경고' "$st (1~2분 뒤 healthy)" }
        else { Add-Result "컨테이너 $c" '완료' $st }
    }
}

$checks = @(
    @{ Name = '화면 http://127.0.0.1/';      Url = 'http://127.0.0.1/';      Min = 200; Max = 399 },
    @{ Name = '데이터 http://127.0.0.1:8081/'; Url = 'http://127.0.0.1:8081/'; Min = 100; Max = 499 },
    @{ Name = '상태 화면 :3001';             Url = 'http://127.0.0.1:3001/'; Min = 200; Max = 399 }
)
if (-not $SkipTunnel -and (Get-Service -Name 'cloudflared' -ErrorAction SilentlyContinue)) {
    $checks += @{ Name = "외부 주소 $PublicUrl"; Url = $PublicUrl; Min = 200; Max = 399 }
}
foreach ($c in $checks) {
    $code = Get-HttpStatus $c.Url
    if ($code -ge $c.Min -and $code -le $c.Max) { Add-Result $c.Name '완료' "$code" } else { Add-Result $c.Name '실패' "응답 $code" }
}

$svc = Get-Service -Name 'cloudflared' -ErrorAction SilentlyContinue
if ($svc) { if ($svc.Status -eq 'Running') { Add-Result '터널 서비스 상태' '완료' 'Running' } else { Add-Result '터널 서비스 상태' '실패' "$($svc.Status)" } }

foreach ($tn in @('Finesse-Deploy', 'Finesse QA', 'Finesse Heartbeat')) {
    $info = Get-ScheduledTask -TaskName $tn -ErrorAction SilentlyContinue | Get-ScheduledTaskInfo -ErrorAction SilentlyContinue
    if (-not $info) { Add-Result "작업 $tn" '실패' '등록 안 됨'; continue }
    # 267011 = 0x41303 "아직 한 번도 실행 안 됨" — 등록 직후엔 실패가 아님
    if ($info.LastTaskResult -eq 0) { Add-Result "작업 $tn" '완료' ("마지막 실행 {0}, 결과 0" -f $info.LastRunTime) }
    elseif ($info.LastTaskResult -eq 267011) { Add-Result "작업 $tn" '완료' ("아직 실행 전, 다음 {0}" -f $info.NextRunTime) }
    else { Add-Result "작업 $tn" '경고' ("결과 {0}" -f $info.LastTaskResult) }
}

$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like '192.168.0.*' } | Select-Object -First 1).IPAddress
if ($ip) { $Manual.Add("학교망 상태 화면 주소가 바뀌었으면 팀에 공유: http://${ip}:3001/status/finesse") }
$Manual.Add('Healthchecks.io: 체크가 Paused 면 첫 핑에 자동으로 풀림. 이름에 PC 번호를 넣었다면 이 PC 로 변경')
$Manual.Add('재부팅 시험: Restart-Computer → 손대지 않고 3~5분 뒤 .\install.ps1 -CheckOnly 로 전부 "이미 됨"/"완료"인지 확인')

Show-Summary
try { Stop-Transcript | Out-Null } catch { }
