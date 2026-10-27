<#
Finesse LLM 추론 서버 시작 스크립트 (Windows PowerShell)

사용 예 (llm-server 폴더에서):
  .\scripts\start-server.ps1 -GpuIndex 1                      # 모델이 models\ 아래 기본 이름일 때
  .\scripts\start-server.ps1 -ModelPath D:\models\qwen2.5-1.5b-instruct-q4_k_m.gguf -GpuIndex 0
  .\scripts\start-server.ps1 -ListDevices                     # Vulkan 장치 번호 확인만

GpuIndex: Vulkan 장치 번호. nvidia-smi 번호와 다르다(내장그래픽이 0번이 될 수 있음).
  -ListDevices 결과에서 "GTX 1050" 이 몇 번인지 보고 넣는다. (11번 PC = 1, 13번 PC = 0)
  잘못 넣으면 오류 없이 내장그래픽으로 돌아 약 12 tok/s 로 느려진다 (설계 v1.2 4.3절).

서버 주소·비밀값은 이 파일에 쓰지 않는다 (레포 Public).
#>
param(
    [string]$ModelPath = "$PSScriptRoot\..\models\qwen2.5-1.5b-instruct-q4_k_m.gguf",
    [int]$GpuIndex = -1,
    [int]$Port = 8081,
    [ValidateSet("off", "json", "schema")][string]$JsonMode = "off",
    [string]$RepeatPenalty = "1.1",
    [switch]$SkipHashCheck,
    [switch]$ListDevices
)
$ErrorActionPreference = "Stop"
Set-Location "$PSScriptRoot\.."

# 베이스 모델(파인튜닝 전) — Qwen 공식 HF 원본과 같은 파일
$ExpectedSha256 = "6A1A2EB6D15622BF3C96857206351BA97E1AF16C30D7A74EE38970E434E9407E"

# 가상환경 python 우선 사용
$Python = if (Test-Path ".\.venv\Scripts\python.exe") { ".\.venv\Scripts\python.exe" } else { "python" }

if (-not (Test-Path $ModelPath)) {
    Write-Host "모델 파일이 없습니다: $ModelPath" -ForegroundColor Red
    Write-Host "README 의 '모델 받기' 를 보고 models\ 폴더에 넣거나 -ModelPath 로 경로를 지정하세요."
    exit 1
}
$ModelPath = (Resolve-Path $ModelPath).Path

if ($ListDevices) {
    Write-Host "Vulkan 장치 목록 (모델을 잠깐 올렸다 내립니다)..."
    & $Python -c "from llama_cpp import Llama; Llama(model_path=r'$ModelPath', n_gpu_layers=-1, n_ctx=512, verbose=True)" 2>&1 |
        Select-String -Pattern "ggml_vulkan|GTX|UHD"
    exit 0
}

if ($GpuIndex -lt 0) {
    Write-Host "-GpuIndex 를 지정하세요. 모르면 먼저 -ListDevices 로 확인하세요." -ForegroundColor Red
    exit 1
}

if (-not $SkipHashCheck) {
    Write-Host "모델 SHA256 확인 중 (1GB, 수십 초)..."
    $hash = (Get-FileHash $ModelPath -Algorithm SHA256).Hash
    if ($hash -ne $ExpectedSha256) {
        Write-Host "SHA256 불일치: $hash" -ForegroundColor Red
        Write-Host "기대값: $ExpectedSha256 (파인튜닝 모델이면 -SkipHashCheck)"
        exit 1
    }
    Write-Host "SHA256 일치" -ForegroundColor Green
}

$env:LLM_GENERATOR = "llama"
$env:LLM_MODEL_PATH = $ModelPath
$env:LLM_JSON_MODE = $JsonMode
$env:LLM_REPEAT_PENALTY = $RepeatPenalty
$env:GGML_VK_VISIBLE_DEVICES = "$GpuIndex"

Write-Host "서버 시작: port=$Port gpu=$GpuIndex json_mode=$JsonMode repeat_penalty=$RepeatPenalty"
Write-Host "이 창을 닫으면 서버가 꺼집니다."
& $Python -m uvicorn app.main:app --host 0.0.0.0 --port $Port
