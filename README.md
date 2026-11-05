# 미션 기반 숏폼 영상 파이프라인

미션별 사진·영상으로 스토리보드와 대본을 만들고, **사람이 수정·승인한 뒤에만** TTS와 영상 생성을 실행한다.
설계는 인계 문서(`vlog-shorts-handoff.md`) 2부를 따르고, 프론트 연동은 [docs/api.md](docs/api.md)에 정리했다.

## 범위

구현됨: 전처리, 사진·영상 분석, 포맷 매칭, 구도 검색·크롭 계산, 스토리보드 생성, 수정·승인, TTS, 영상 어댑터, 합성, 저장, CLI, API 서버.

제외됨 (RAG 수집): `rag/datasets.py`, `rag/build.py`, `vlog corpus build`, `vlog corpus eval`, `data/dataset_licenses.md`.
구도 데이터가 없으면 검색 결과가 비어 규칙 기반 크롭으로 동작하고, 컷에 `rule_crop`으로 표시한다.

## 준비

이 PC의 상태(2026-11-06): Python 3.12(3.11은 없어 `.venv`를 3.12로 만듦), Ollama, ffmpeg, Docker, ComfyUI 포터블판(`F:\ComfyUI\ComfyUI_windows_portable`) 설치됨.

```bash
docker compose up -d
```

```bash
copy .env.example .env
```

```bash
.venv\Scripts\alembic upgrade head
```

```bash
.venv\Scripts\vlog doctor
```

## API 서버

프론트가 호출하는 서버다. 사진·영상 제출, 스토리보드 조회·수정·승인, 영상 생성, 결과 재생을 제공한다. 호출 순서와 오류 처리는 [docs/api.md](docs/api.md)를 본다.

이 PC에서만 쓸 때:

```bash
.venv\Scripts\vlog serve
```

같은 네트워크의 다른 기기에서 접속할 때는 `.env`에 `API_KEY`를 넣고, `config.yaml`의 `api.cors_origins`에 프론트 주소를 적고, Windows 방화벽에서 8000번 포트를 허용한 뒤 이 PC의 IP로 연다. 키 없이는 시작을 거부한다.

```bash
.venv\Scripts\vlog serve --host 192.168.0.2
```

브라우저에서 `/docs`를 열면 직접 호출해 볼 수 있다. API를 바꾸면 프론트 팀원에게 줄 명세를 다시 만든다.

```bash
.venv\Scripts\vlog openapi
```

### Backend와 연결

iOS 앱 → 도메인 → NPM → pfSense → Backend VM(Docker) → 이 서버 순서로 연결한다. 이 서버는 `127.0.0.1`에만 열고, **이 PC가 VM으로 SSH 접속해 역방향 터널**을 연다. pfSense나 Windows 방화벽에 이 서버용 규칙을 추가하지 않는다. VM 쪽 설치는 Backend 브랜치(`feat/backend`)의 `deploy/vm/README.md`를 본다.

1. `.env`에 `API_KEY`를 넣고 기본값으로 `vlog serve`를 띄운다.
2. 터널 전용 키를 만든다(비밀번호 없음, 이 키는 VM에서 포워딩만 허용된다).

```powershell
ssh-keygen -t ed25519 -f $HOME\.ssh\vlog_tunnel -N '""' -C vlog-tunnel
```

3. 공개키를 VM에 올린다. 이때 한 번은 VM 비밀번호를 직접 입력한다.

```powershell
scp -P 2222 $HOME\.ssh\vlog_tunnel.pub <사용자>@192.168.0.17:/tmp/vlog_tunnel.pub
```

4. VM에서 `sudo bash deploy/vm/allow-ai-tunnel.sh <사용자> /tmp/vlog_tunnel.pub`를 실행한다.
5. 이 PC에서 터널을 연다. 끊기면 10초 뒤 다시 연결한다.

```powershell
pwsh -File scripts\ai-tunnel.ps1 -Server 192.168.0.17 -User <사용자>
```

6. 로그온할 때 자동으로 열리게 하려면 작업 스케줄러에 등록한다(`vlog serve`도 같은 방식으로 등록할 수 있다).

```powershell
Register-ScheduledTask -TaskName vlog-ai-tunnel -Trigger (New-ScheduledTaskTrigger -AtLogOn) -Action (New-ScheduledTaskAction -Execute pwsh -Argument "-NoProfile -WindowStyle Hidden -File `"$PWD\scripts\ai-tunnel.ps1`" -Server 192.168.0.17 -User <사용자>")
```

7. VM에서 확인한다.

```bash
curl -H "Authorization: Bearer <API_KEY>" http://172.17.0.1:18000/api/v1/health
```

Backend `.env`에는 `SHORTFORM_AI_BASE_URL=http://host.docker.internal:18000/api/v1`과 같은 `API_KEY`를 넣는다. 검토 화면이 있는 iOS 앱이 나오기 전에는 `SHORTFORM_AI_ENABLED=false`로 둔다. 기존 앱은 검토 단계를 몰라 30분 기다리다 실패한다.

- **컷 구성:** 제출한 파일 하나당 컷 하나이며 순서를 바꾸거나 빼지 않는다. 템플릿은 컷 역할, 말투, 길이, 샷 후보, 화면 문구 규칙만 제공한다.
- **사진 컷:** AI 영상을 만든다. **영상 컷:** 원본을 쓰고 AI가 프레임을 보고 고른 구간을 제안한다. 프론트에서 구간을 고칠 수 있다.
- **작업 처리:** 작업자 스레드 하나가 순서대로 처리한다. 서버를 다시 켜면 실행 중이던 작업을 이어서 하며, 끝난 컷은 재사용한다. 그래서 서버 프로세스는 하나만 띄운다.

## CLI 사용

```bash
.venv\Scripts\vlog plan ./photos --memo "제주 2박 3일 혼자 여행"
```

사진 순서를 직접 정하려면 파일 이름을 숫자로 시작하게 하고(`1.jpg`, `2_카페.png`) `--order number`를 붙인다. 번호순으로 전부 사용하며, 흐리거나 비슷한 사진도 자동으로 빼지 않는다. 번호는 `photo_id`에 그대로 들어간다(`2_카페.png` → `p02`).

```bash
.venv\Scripts\vlog plan ./photos --order number
```

```bash
.venv\Scripts\vlog storyboard export <run_id> --out storyboard.yaml
```

```bash
.venv\Scripts\vlog storyboard import <run_id> --file storyboard.yaml
```

```bash
.venv\Scripts\vlog storyboard regen <run_id> --cut c_7f3a --field narration
```

```bash
.venv\Scripts\vlog storyboard diff <run_id> --from 1 --to 2
```

```bash
.venv\Scripts\vlog storyboard approve <run_id> --version 2
```

API 키와 ComfyUI 없이 전체 흐름을 돌리려면 무음 TTS와 Ken Burns를 쓴다.

```bash
.venv\Scripts\vlog render <run_id> --tts silent --video kenburns
```

```bash
.venv\Scripts\vlog render <run_id>
```

```bash
.venv\Scripts\vlog regen <run_id> --cut c_7f3a --seed 42
```

## 음악 전용 템플릿과 배경 음악

템플릿에 `audio: {mode: music, music: <곡 id>}`를 적으면 나레이션과 TTS 없이 음악만 넣고, 컷 길이는 템플릿의 `sec`를 그대로 따른다(`reel_mood_trip_temp_v1`). 나레이션 템플릿에 음악을 고르면 나레이션 구간에서 작아지는 배경 음악이 된다.

곡은 라이선스를 확인한 뒤 `assets/music/`(git 제외)에 넣고 [data/music.yaml](data/music.yaml)에 등록한다. 파일이 있는 곡만 `/options`, `/catalog`에 나온다. 음악 전용 템플릿에 쓸 곡이 없으면 승인과 생성이 거부된다. 실행마다 음악은 제출 때 `music`(Backend는 `musicId`)으로 고르거나 스토리보드의 `music_id`로 바꾼다.

## 컷별 효과 (나노바나나 → ComfyUI 또는 Veo)

`storyboard.yaml`의 컷마다 `effect`를 적으면 승인 후 그 컷의 사진을 나노바나나로 다시 그려 영상 입력으로 쓴다. 값은 `data/image_effects.yaml`의 이름(`film_look`, `golden_hour`, `rain` 등)이나 공백이 있는 영어 문장이다. `null`인 컷은 원본 사진을 쓰며 이미지 API를 호출하지 않는다. 얼굴이 안 보이는 인물 사진에는 얼굴을 만들지 말라는 지시가 함께 들어간다.

`.env`에 `GEMINI_API_KEY`를 넣고, 수정한 스토리보드를 가져와 승인한 뒤 이미지만 먼저 확인한다. 결과는 `storage/runs/<run_id>/images/`에 저장된다.

```bash
.venv\Scripts\vlog images <run_id>
```

마음에 들지 않는 컷은 다시 만든다.

```bash
.venv\Scripts\vlog images <run_id> --cut c_7f3a --again
```

요금 없이 영상 흐름까지 보려면 Ken Burns와 무음 TTS를 쓴다. 효과 이미지는 재사용된다.

```bash
.venv\Scripts\vlog render <run_id> --tts silent --video kenburns
```

```bash
.venv\Scripts\vlog render <run_id> --video comfyui
```

Veo는 Gemini API 프리뷰가 2026-10-22에 종료되어 Google Cloud의 정식 모델을 쓴다. 결제가 연결된 프로젝트에서 Vertex AI API를 켜고, Google Cloud CLI를 설치해 로그인한 뒤, `.env`에 `GOOGLE_CLOUD_PROJECT`를 넣는다.

```bash
gcloud auth login
```

```bash
.venv\Scripts\vlog render <run_id> --video veo
```

Veo는 4·6·8초만 만들기 때문에 컷 길이 이상 중 가장 짧은 값으로 요청하고 남는 부분은 합성에서 자른다. 생성에 실패한 컷은 Ken Burns로 대체되고 표시되며, 다시 실행하면 그 컷만 재시도한다. 키나 권한 문제는 대체하지 않고 멈춘다.

## 테스트

`tests/`는 외부 API, GPU, PostgreSQL 없이 돌도록 작성했다. 실제 ffmpeg 테스트는 ffmpeg가 PATH에 있을 때만 실행된다.

```bash
.venv\Scripts\python -m pytest
```

## 계획과 다르게 정한 점

- **Ollama 모델:** 계획의 Qwen3-VL과 EXAONE 대신 이 PC에 설치된 모델을 쓴다. 사진 분석은 `gemma4:26b`, 대본은 `gemma4:latest`다. `gemma4:latest`는 Ollama 0.30.10에서 사진을 제대로 보지 못해 사진 분석에 쓰지 않는다. `config.yaml`에서 바꿀 수 있다.
- **나레이션 길이 초과:** 계획은 "로컬 LLM으로 줄여 재합성"이지만, 승인되지 않은 문장으로 TTS를 호출하게 되므로 줄인 문장을 새 초안으로 저장하고 멈춘다. 다시 승인하면 바뀐 컷만 재합성한다. 나레이션이 잠긴 컷은 사람이 직접 줄여야 한다.
- **`runs.status`:** 초안이 생기기 전 상태로 `planning`을 추가했다.
- **`vlog storyboard revert`, `versions`:** 되돌리기와 버전 조회 명령을 추가했다. 승인은 최신 버전만 가능하다.
- **`--tts silent`:** 요금 없이 흐름을 시험하는 어댑터를 추가했다.

## 아직 확정되지 않은 값

- `workflows/wan22_i2v.json`: 실제 ComfyUI 없이 노드 이름을 기억에 의존해 쓴 초안이다. M0에서 ComfyUI로 API 형식을 내보내 교체하고, `{{PROMPT}}` 같은 자리표시자를 다시 넣는다.
- `config.yaml`의 `video.models`: 파일명은 예시다. 실제 받은 파일명으로 맞춘다.
- `tts.voice_id`: 비어 있다. M3 비교 후 정한다.
- `data/formats/*.yaml`: 3개 모두 초안이다. 처음 만들 포맷은 아직 정해지지 않았다.
- `pipeline/detect.py`: OpenCV 얼굴 검출만 있다. 구도 데이터 구축과 같은 검출 도구로 바꿔야 한다.
- 이미지 임베딩: 모델 미정이라 입력 사진 임베딩을 만들지 않으며, 지금은 배치 벡터와 크롭 점수로만 검색한다. `IMAGE_EMBEDDING_DIM = 768`도 임시 값이다.
- 글꼴: `assets/fonts`에 Pretendard가 없으면 Malgun Gothic으로 대체한다.
