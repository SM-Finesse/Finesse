<#
추론 서버 동작 확인 (다른 PC에서도 실행 가능)

사용 예 (llm-server 폴더에서):
  .\scripts\check-server.ps1                           # 이 PC의 서버
  .\scripts\check-server.ps1 -Server http://<서버 IP>:8081
  .\scripts\check-server.ps1 -Server http://<서버 IP>:8081 -Heavy   # 헤비 8챕터도 확인

확인 항목: /health, 샘플 light 요청 3회(200 이 1회 이상이면 통과).
파인튜닝 전 모델은 light 형식 오류(502)가 섞여 나오는 것이 정상이다.
-Heavy: 샘플(tests\samples\heavy_chapters.json)로 헤비 8챕터를 1번씩 요청 → 8개 모두 200 + 각주가 있으면 통과.
  헤비는 서버가 검사 실패 시 다시 생성하거나 대체 문장을 보내므로 502 가 나오면 이상이다.
#>
param(
    [string]$Server = "http://localhost:8081",
    [int]$Tries = 3,
    [switch]$Heavy
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
$failed = $false
if ($ok -gt 0) { Write-Host "light 통과: 200 $ok/$Tries" -ForegroundColor Green } else { Write-Host "light 실패: 200 없음" -ForegroundColor Red; $failed = $true }

if ($Heavy) {
    $sample = Get-Content "$PWD\tests\samples\heavy_chapters.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    $chapters = 'tr_trend','playstyle','attack','defense','strength_split','comeback_rate','session_vs_slope','rivals'
    $hok = 0
    foreach ($ch in $chapters) {
        $json = @{ chapter_id = $ch; data = $sample.$ch } | ConvertTo-Json -Depth 5 -Compress
        $t = Get-Date
        try {
            $r = Invoke-WebRequest "$Server/v1/comment/heavy-chapter" -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json)) -UseBasicParsing -TimeoutSec 60
            $res = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
            $sec = ((Get-Date) - $t).TotalSeconds
            if ($res.chapter_id -eq $ch -and $res.footnote) { $hok++ }
            Write-Host ("[heavy {0,-16}] 200 ({1:N1}s) {2}" -f $ch, $sec, $res.footnote)
        } catch {
            $sec = ((Get-Date) - $t).TotalSeconds
            Write-Host ("[heavy {0,-16}] 실패 ({1:N1}s) {2}" -f $ch, $sec, $_.Exception.Message) -ForegroundColor Yellow
        }
    }
    if ($hok -eq $chapters.Count) { Write-Host "heavy 통과: 8/8" -ForegroundColor Green } else { Write-Host "heavy 실패: $hok/8" -ForegroundColor Red; $failed = $true }
}
if ($failed) { exit 1 }
