<#
추론 서버 동작 확인 (다른 PC에서도 실행 가능)

사용 예 (llm-server 폴더에서):
  .\scripts\check-server.ps1                           # 이 PC의 서버
  .\scripts\check-server.ps1 -Server http://<서버 IP>:8081

확인 항목: /health, 샘플 light 요청 3회(200 이 1회 이상이면 통과).
파인튜닝 전 모델은 형식 오류(502)가 섞여 나오는 것이 정상이다.
#>
param(
    [string]$Server = "http://localhost:8081",
    [int]$Tries = 3
)
Set-Location "$PSScriptRoot\.."

try {
    $h = Invoke-RestMethod "$Server/health" -TimeoutSec 5
    Write-Host "health: $($h.status)" -ForegroundColor Green
} catch {
    Write-Host "health 실패: 서버가 꺼져 있거나 주소·방화벽(TCP $([uri]$Server).Port) 문제" -ForegroundColor Red
    exit 1
}

$body = [IO.File]::ReadAllBytes("$PWD\tests\samples\light_testuser.json")
$ok = 0
for ($i = 1; $i -le $Tries; $i++) {
    $t = Get-Date
    try {
        $r = Invoke-WebRequest "$Server/v1/comment/light" -Method Post -ContentType "application/json; charset=utf-8" -Body $body -UseBasicParsing -TimeoutSec 60
        $text = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        $sec = ((Get-Date) - $t).TotalSeconds
        Write-Host ("[{0}/{1}] 200 ({2:N1}s) {3}" -f $i, $Tries, $sec, $text)
        $ok++
    } catch {
        $sec = ((Get-Date) - $t).TotalSeconds
        Write-Host ("[{0}/{1}] 실패 ({2:N1}s) {3}" -f $i, $Tries, $sec, $_.Exception.Message) -ForegroundColor Yellow
    }
}
if ($ok -gt 0) { Write-Host "통과: 200 $ok/$Tries" -ForegroundColor Green } else { Write-Host "실패: 200 없음" -ForegroundColor Red; exit 1 }
