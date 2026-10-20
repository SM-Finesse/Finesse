<#
=====================================================================
 deploy.ps1 을 Windows 작업 스케줄러에 등록 (몇 분마다 자동 실행)

   .\install-task.ps1                   # 등록 (기본 2분 간격)
   .\install-task.ps1 -IntervalMinutes 5
   .\install-task.ps1 -Uninstall        # 등록 해제 (컨테이너는 그대로 둠)

 - 현재 로그인한 사용자로, "로그인돼 있을 때만" 실행 (Docker Desktop 이 사용자 로그인 후에 켜지기 때문)
 - 이미 실행 중이면 새로 시작하지 않음 (겹침 방지)
=====================================================================
#>
[CmdletBinding()]
param(
    [int]$IntervalMinutes = 2,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$TaskName = 'Finesse-Deploy'

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "작업 '$TaskName' 해제 완료 (실행 중인 컨테이너는 그대로입니다)"
    } else {
        Write-Host "등록된 작업 '$TaskName' 없음"
    }
    exit 0
}

$script = Join-Path $PSScriptRoot 'deploy.ps1'
if (-not (Test-Path $script)) { throw "deploy.ps1 을 찾을 수 없음: $script" }
if (-not (Test-Path (Join-Path $PSScriptRoot '.env'))) {
    throw '.env 가 없습니다. .env.example 을 .env 로 복사해 PC에 맞게 수정한 뒤 다시 실행하세요.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'docker 명령이 없습니다. Docker Desktop 설치 후 다시 실행하세요.'
}

$user = "$env:USERDOMAIN\$env:USERNAME"
$arg  = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $script

$action   = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg -WorkingDirectory $PSScriptRoot
$repeat   = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
              -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes) `
              -RepetitionDuration (New-TimeSpan -Days 3650)
$logon    = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal= New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
              -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
              -ExecutionTimeLimit (New-TimeSpan -Minutes 20)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($repeat, $logon) `
    -Principal $principal -Settings $settings -Force `
    -Description 'Finesse 자동 배포: GitHub deploy 브랜치 target.env 의 태그로 컨테이너를 맞춤 (infra/deploy/deploy.ps1)' | Out-Null

Write-Host "작업 '$TaskName' 등록 완료 — $IntervalMinutes 분마다, 그리고 로그인할 때 실행"
Write-Host "로그: $(Join-Path $PSScriptRoot 'logs\deploy.log')"
