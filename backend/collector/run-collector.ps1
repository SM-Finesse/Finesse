# 학습 데이터 수집기 실행 (collect 브랜치 전용) — 학습 데이터 수집 서버 작업지시서 4절.
# 1) 지금 코드로 jar를 다시 빌드한다 — calc_commit(git HEAD)과 실행 코드가 같아야 한다.
# 2) 수집기를 띄우고, 비정상 종료(전원·JVM 오류 등)면 1분 뒤 다시 띄운다 — 수집기는 users.csv를 보고 이어서 한다.
#    이미 실행 중(3)·설정 오류(1)·중단(2 — failure-pause-seconds를 0으로 둔 경우)이면 다시 띄우지 않는다.
# 3) 명단 끝까지 수집해도(0) 창을 닫지 않고 기다리다가, targets.csv가 바뀌면 같은 창에서 이어서 수집한다(-Once면 그냥 끝낸다).
#    창은 하나만 — 이미 다른 창이 실행 중이거나 기다리는 중이면 새 창은 바로 끝낸다(3).
#
# 예) powershell -ExecutionPolicy Bypass -File collector\run-collector.ps1 -DataDir D:\finesse-collector
param(
    [string]$DataDir = "$env:USERPROFILE\finesse-collector",
    [int]$MaxRestarts = 20,
    [switch]$Once,
    [int]$WatchSeconds = 60
)

# 창 하나만 — 수집기 락(collector.lock)은 java가 도는 동안만 잡혀 있어서, 기다리는 창끼리는 이걸로 막는다
$windowMutex = New-Object System.Threading.Mutex($false, 'Local\FinesseCollectorWindow')
$owned = $false
try { $owned = $windowMutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $owned = $true }
if (-not $owned) {
    Write-Warning "데이터 수집용 서버가 이미 다른 창에서 실행 중입니다 — 이 창은 끝냅니다"; exit 3
}

$jdk = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
if (Test-Path "$jdk\bin\java.exe") { $env:JAVA_HOME = $jdk }
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Error "JDK 25를 찾지 못했습니다 — JAVA_HOME을 지정한 뒤 다시 실행하세요"; exit 1
}

Set-Location (Split-Path -Parent $PSScriptRoot)   # backend 폴더
function Build-Jar {
    # 1번 서버(bootRun)의 Gradle 데몬과 섞이지 않게, 빌드 뒤 데몬을 남기지 않는다
    .\gradlew.bat bootJar -q --no-daemon | Out-Host   # 빌드 출력이 함수 반환값에 섞이지 않게 화면으로만
    if ($LASTEXITCODE -ne 0) { return $null }
    return Get-ChildItem build\libs\*.jar | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
}
$jar = Build-Jar
if (-not $jar) { Write-Error "빌드 실패"; exit 1 }

$targets = Join-Path $DataDir 'targets.csv'
function Get-TargetsStamp {
    $f = Get-Item $targets -ErrorAction SilentlyContinue
    if ($f) { return "$($f.LastWriteTimeUtc.Ticks):$($f.Length)" } else { return '' }
}

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
        $stamp = Get-TargetsStamp
        & "$env:JAVA_HOME\bin\java.exe" -jar $jar.FullName --spring.profiles.active=collector "--collector.data-dir=$DataDir"
        $code = $LASTEXITCODE
        if ($code -eq 0 -and -not $Once) {
            # 4) 새 명단을 기다린다 — 절전 방지는 그대로 유지
            Write-Host ""
            Write-Host "[$(Get-Date -Format 'MM-dd HH:mm')] 명단 끝까지 수집했습니다. 창을 열어 둔 채 새 명단을 기다립니다."
            Write-Host "  targets.csv에 유저를 추가하면 이 창에서 자동으로 이어서 수집합니다 ($WatchSeconds초마다 확인)."
            do { Start-Sleep -Seconds $WatchSeconds } while ((Get-TargetsStamp) -eq $stamp)
            Start-Sleep -Seconds 5   # 저장이 끝날 때까지 잠깐
            Write-Host "[$(Get-Date -Format 'MM-dd HH:mm')] 명단이 바뀌었습니다 — 지금 코드로 다시 빌드하고 이어서 수집합니다"
            # 기다리는 동안 코드가 바뀌었을 수 있다 — calc_commit(git HEAD)과 실행 jar를 맞추려고 매번 다시 빌드
            $rebuilt = Build-Jar
            if ($rebuilt) { $jar = $rebuilt } else { Write-Warning "빌드 실패 — 이전 jar로 수집합니다(코드가 커밋 안 됐으면 수집기가 시작을 거부합니다)" }
            $restarts = 0
            continue
        }
        if ($code -in 0, 1, 2, 3) { break }
        $restarts++
        if ($restarts -gt $MaxRestarts) { Write-Warning "재시작 $MaxRestarts회 초과 — 멈춥니다"; break }
        Write-Warning "수집기가 비정상 종료(코드 $code) — 60초 뒤 이어서 다시 실행합니다 ($restarts/$MaxRestarts)"
        Start-Sleep -Seconds 60
    }
} finally {
    [Finesse.Power]::SetThreadExecutionState($ES_CONTINUOUS) | Out-Null   # 절전 방지 해제
    $windowMutex.ReleaseMutex()
}
exit $code
