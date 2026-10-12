# 학습 데이터 수집기 실행 (collect 브랜치 전용) — 학습 데이터 수집 서버 작업지시서 4절.
# 1) 지금 코드로 jar를 다시 빌드한다 — calc_commit(git HEAD)과 실행 코드가 같아야 한다.
# 2) 수집기를 띄우고, 비정상 종료(전원·JVM 오류 등)면 1분 뒤 다시 띄운다 — 수집기는 users.csv를 보고 이어서 한다.
#    정상 완료(0)·연속 실패 중단(2)·이미 실행 중(3)·설정 오류(1)면 다시 띄우지 않는다.
#
# 예) powershell -ExecutionPolicy Bypass -File collector\run-collector.ps1 -DataDir D:\finesse-collector
param(
    [string]$DataDir = "$env:USERPROFILE\finesse-collector",
    [int]$MaxRestarts = 20
)

$jdk = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
if (Test-Path "$jdk\bin\java.exe") { $env:JAVA_HOME = $jdk }
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Error "JDK 25를 찾지 못했습니다 — JAVA_HOME을 지정한 뒤 다시 실행하세요"; exit 1
}

Set-Location (Split-Path -Parent $PSScriptRoot)   # backend 폴더
.\gradlew.bat bootJar -q --no-daemon   # 1번 서버(bootRun)의 Gradle 데몬과 섞이지 않게, 빌드 뒤 데몬을 남기지 않는다
if ($LASTEXITCODE -ne 0) { Write-Error "빌드 실패"; exit 1 }
$jar = Get-ChildItem build\libs\*.jar | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1

# 3) 수집하는 동안 PC가 절전 모드로 들어가지 않게 한다(화면은 꺼져도 된다). 이 창이 닫히면 원래 절전 설정으로 돌아간다.
#    Windows 절전 타이머만 막는다 — 사용자가 직접 절전·덮개 닫기를 하거나 업데이트로 재부팅되는 것은 막지 못한다.
Add-Type -Namespace Finesse -Name Power -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
$ES_CONTINUOUS = [uint32]2147483648       # 0x80000000 — 아래 상태를 해제할 때까지 유지
$ES_SYSTEM_REQUIRED = [uint32]1           # 0x00000001 — 시스템 절전 타이머를 막는다
if ([Finesse.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED) -eq 0) {
    Write-Warning "절전 방지를 켜지 못했습니다 — 전원 옵션에서 절전을 '안 함'으로 바꾼 뒤 실행하세요"
} else {
    Write-Host "수집하는 동안 절전 모드를 막습니다 (이 창을 닫으면 해제)"
}

$restarts = 0
try {
    while ($true) {
        & "$env:JAVA_HOME\bin\java.exe" -jar $jar.FullName --spring.profiles.active=collector "--collector.data-dir=$DataDir"
        $code = $LASTEXITCODE
        if ($code -in 0, 1, 2, 3) { break }
        $restarts++
        if ($restarts -gt $MaxRestarts) { Write-Warning "재시작 $MaxRestarts회 초과 — 멈춥니다"; break }
        Write-Warning "수집기가 비정상 종료(코드 $code) — 60초 뒤 이어서 다시 실행합니다 ($restarts/$MaxRestarts)"
        Start-Sleep -Seconds 60
    }
} finally {
    [Finesse.Power]::SetThreadExecutionState($ES_CONTINUOUS) | Out-Null   # 절전 방지 해제
}
exit $code
