# /llm-server

Finesse LLM 추론 서버 (FastAPI + llama-cpp-python, Vulkan). 라이트·헤비 코멘트 생성.

- 담당 브랜치: `llm` (윤세연)
- 설계 기준: Finesse — LLM/AI 파트 설계 v1.2
- API: `GET /health`, `POST /v1/comment/light`, `POST /v1/comment/heavy-chapter` (포트 8081)

## 재현 기준 버전 — `llm-base-v0` (파인튜닝 전 베이스 모델)

| 항목 | 값 |
|---|---|
| 서버 코드 | 태그 `llm-base-v0` |
| 모델 | `qwen2.5-1.5b-instruct-q4_k_m.gguf` (1.12GB) — Qwen 공식 `Qwen/Qwen2.5-1.5B-Instruct-GGUF` 원본 |
| 모델 SHA256 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| llama-cpp-python | 0.3.35 Vulkan 공식 wheel, SHA256 `fa06c0c184801410be1ffdcc32cf5b57d92b23a6dff9e90e5870dea8710a233c` |
| Python | 3.11.9 (64bit) |
| 확인 환경 | Windows, GTX 1050 (VRAM 2GB), NVIDIA 드라이버 582.42 — 학교 11번·13번 PC |
| 실행 설정 | `LLM_JSON_MODE=off`, `LLM_REPEAT_PENALTY=1.1`, n_ctx 2048, 디코딩 temperature 0.4 / top_p 0.9 / max_tokens light 377·heavy 119 |

모델 파일과 wheel 백업본은 담당자 Google Drive에 있다 (링크는 재현 가이드 문서 참고, 레포에는 넣지 않음).

## 새 PC에 처음 설치하기 (Windows)

1. **Python 3.11.9 (64bit)** 설치 — python.org, 설치 화면에서 "Add python.exe to PATH" 체크
2. **NVIDIA 드라이버** 설치 확인 — `nvidia-smi` 가 GTX 1050 을 보여 주면 된다 (Vulkan 런타임 포함)
3. **코드 받기** — 경로는 짧게 (Windows 260자 경로 제한 회피)
   ```powershell
   cd C:\
   git clone https://github.com/SM-Finesse/Finesse.git
   cd C:\Finesse
   git switch llm
   git checkout llm-base-v0      # 재현 기준 버전으로 고정 (최신을 쓰려면 생략)
   cd llm-server
   ```
4. **가상환경 + 설치**
   ```powershell
   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
   python -m venv .venv
   .\.venv\Scripts\Activate.ps1
   python -m pip install -r requirements-server.txt
   ```
5. **모델 받기** — 둘 중 하나로 받아 `llm-server\models\` 폴더에 둔다 (`models\` 는 git 에서 제외됨)
   - 공식: https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/main/qwen2.5-1.5b-instruct-q4_k_m.gguf (Download)
   - 백업: 담당자 Google Drive 링크
6. **GPU 번호 확인** — 목록에서 `GTX 1050` 이 몇 번인지 본다
   ```powershell
   .\scripts\start-server.ps1 -ListDevices
   ```
7. **서버 시작** — 이 창은 닫지 않는다
   ```powershell
   .\scripts\start-server.ps1 -GpuIndex 1      # 6번에서 본 번호
   ```
   `SHA256 일치` → `생성기: llama` → `워밍업 완료` → `Uvicorn running on http://0.0.0.0:8081` 이 나오면 성공
8. **방화벽** — 다른 PC(백엔드)에서 접속하려면 관리자 PowerShell 에서 1회
   ```powershell
   New-NetFirewallRule -DisplayName "Finesse-LLM-8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow
   ```

## 동작 확인 (성공 기준)

다른 PowerShell 창(가상환경 불필요)에서:
```powershell
cd C:\Finesse\llm-server
.\scripts\check-server.ps1                               # 같은 PC
.\scripts\check-server.ps1 -Server http://<서버 IP>:8081  # 다른 PC에서
```
- `health: ok`, light 3회 중 200 이 1회 이상 → 통과
- 서버 창 로그 `light 생성 …s finish=… prompt=… gen=… seed=…` 의 생성 속도가 약 50 tok/s (gen ÷ 시간).
  약 12 tok/s 면 GPU 번호가 틀려 내장그래픽으로 도는 것 → 6번부터 다시
- 파인튜닝 전 모델이라 502(형식 오류)가 섞이는 것은 정상

## 백엔드 연결

백엔드 실행 시 `--llm.servers=http://<LLM PC IP>:8081,...` 로 주소를 넘긴다 (백엔드 실행 스크립트, 레포에는 넣지 않음).
주소만 바꾸면 되고 API 경로·형식은 그대로다.

## 자주 생기는 문제

| 증상 | 원인 · 해결 |
|---|---|
| `Activate.ps1` 실행이 막힘 | `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` 후 다시 |
| 스크립트 실행 시 "보안 경고 … 실행하시겠습니까?" | 브라우저로 받은 파일이라서다. `R`(한 번 실행)을 누르거나 `Unblock-File .\scripts\*.ps1` (git clone 으로 받으면 나오지 않음) |
| `llama.dll` 로드 실패, WinError 4551 | Windows 스마트 앱 컨트롤이 차단 → 끄기 (학교 관리 PC는 관리자 확인) |
| 설치 중 경로 길이 오류 | 레포를 `C:\Finesse` 처럼 짧은 경로에 둔다 |
| 생성이 약 12 tok/s | GPU 번호 오지정 → `-ListDevices` 로 다시 확인 |
| 포트 8081 사용 중 | 이전 서버가 떠 있음 → `Get-NetTCPConnection -LocalPort 8081` 로 PID 확인 후 종료 |

## 개발 (모델 없이)

```powershell
python -m pip install -r requirements.txt ruff pytest
ruff check .
python -m pytest -q
```
환경변수를 주지 않으면 Mock 생성기로 동작한다 (`LLM_GENERATOR` 설명은 `app/generator.py`).
