# 사진 기반 숏폼 브이로그 파이프라인

사진 묶음으로 스토리보드를 만들고, **사람이 수정·승인한 뒤에만** TTS와 영상 생성을 실행한다.
설계는 인계 문서(`vlog-shorts-handoff.md`) 2부를 따른다.

## 범위

구현됨: 전처리, 사진 분석, 포맷 매칭, 구도 검색·크롭 계산, 스토리보드 생성, 수정·승인, TTS, 영상 어댑터, 합성, 저장, CLI.

제외됨 (RAG 수집): `rag/datasets.py`, `rag/build.py`, `vlog corpus build`, `vlog corpus eval`, `data/dataset_licenses.md`.
구도 데이터가 없으면 검색 결과가 비어 규칙 기반 크롭으로 동작하고, 컷에 `rule_crop`으로 표시한다.

## 준비

이 PC의 상태(2026-11-05): Python 3.12(3.11은 없어 `.venv`를 3.12로 만듦), Ollama, ffmpeg, ComfyUI 포터블판(`F:\ComfyUI\ComfyUI_windows_portable`) 설치됨. WSL과 Docker는 없다. 둘 다 관리자 권한이 필요하며, WSL 설치 후에는 재부팅이 필요할 수 있다.

```bash
wsl --install --no-distribution
```

```bash
winget install --id Docker.DockerDesktop --exact --source winget
```

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

## 사용

```bash
.venv\Scripts\vlog plan ./photos --memo "제주 2박 3일 혼자 여행"
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

## 테스트

`tests/`는 외부 API, GPU, PostgreSQL 없이 돌도록 작성했다. 실제 ffmpeg 테스트는 ffmpeg가 PATH에 있을 때만 실행된다.

```bash
.venv\Scripts\python -m pytest
```

## 계획과 다르게 정한 점

- **Ollama 모델:** 계획의 Qwen3-VL과 EXAONE 대신, 이 PC에 설치된 `gemma4:latest`를 사진 분석과 대본에 함께 쓴다. `config.yaml`에서 바꿀 수 있다.
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
