# AI-Video-Generate

이 저장소에는 프로젝트 두 개가 함께 있다.

- **영상 파이프라인 (vlog):** 사진·영상으로 숏폼 브이로그를 만드는 Python 서버. 아래 [미션 기반 숏폼 영상 파이프라인](#vlog-pipeline)
- **Backend:** 앱이 호출하는 Spring 서버. 숏폼 제작은 vlog 서버에 중계한다. 아래 [Hackathon Backend](#hackathon-backend)

<a id="vlog-pipeline"></a>

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

---

<a id="hackathon-backend"></a>

# Hackathon Backend

커뮤니티 게시물·검색·랜덤 탐색 API: [COMMUNITY_API.md](docs/COMMUNITY_API.md)

커뮤니티 댓글 작성·조회·수정·삭제 API: [COMMUNITY_COMMENTS_API.md](docs/COMMUNITY_COMMENTS_API.md)

일정 시작·종료, 이동 경로·걸음수, 나의 일기장 API: [diary-api.md](docs/diary-api.md)

진행 중인 코스의 완료 장소를 유지하고 남은 일정 수정: [COURSE_REFINEMENT_API.md](docs/COURSE_REFINEMENT_API.md)

숏폼 제작 중계 API 및 AI 연동 가안: [SHORTFORM_API.md](docs/SHORTFORM_API.md)

Java 21, Spring Boot, Gradle, PostgreSQL로 구성된 백엔드 프로젝트입니다.

## 1. PostgreSQL 실행

Docker Desktop을 실행한 뒤 프로젝트 루트의 VS Code 터미널에서 실행합니다.

```powershell
docker compose up -d
docker compose ps
```

`hackathon-postgres`의 상태가 `healthy`이면 준비가 끝난 것입니다.

기본 접속 정보:

- 주소: `localhost:5433`
- 데이터베이스: `hackathon`
- 사용자: `postgres`
- 비밀번호: `postgres`

값을 변경하려면 `.env.example`을 복사해 `.env` 파일을 만들고 수정합니다. `.env`는 Git에 포함되지 않습니다.

## 2. Spring Boot 실행

VS Code에서 `BackendApplication.java`의 `Run`을 누르거나 터미널에서 실행합니다.

```powershell
.\gradlew.bat bootRun
```

서버는 기본적으로 `http://localhost:8080`에서 실행됩니다.

## 3. 테스트

통합 테스트는 Docker의 PostgreSQL 17 Testcontainers를 사용합니다. Docker Desktop을 실행해야 합니다.

```powershell
.\gradlew.bat test
```

## 4. 종료

```powershell
docker compose down
```

## 인증 API

### 회원가입

`POST /api/auth/signup`

```json
{
  "email": "user@example.com",
  "name": "홍길동",
  "password": "password123"
}
```

### 로그인

`POST /api/auth/login`

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

회원가입과 로그인은 `accessToken`, `refreshToken`, 각각의 만료 시간과 `user`를 반환합니다.
Access Token은 1시간, Refresh Token은 로그인 시점부터 고정 7일 동안 유효합니다.

### 토큰 재발급

`POST /api/auth/refresh`

```json
{
  "refreshToken": "CURRENT_REFRESH_TOKEN"
}
```

재발급 시 Access Token과 Refresh Token이 모두 교체됩니다. 이전 Refresh Token을 다시 사용하면 해당 로그인 세션 전체가 폐기됩니다.

### 로그아웃

`POST /api/auth/logout`

```json
{
  "refreshToken": "CURRENT_REFRESH_TOKEN"
}
```

로그아웃은 해당 Refresh Session을 폐기하며 성공 시 `204 No Content`를 반환합니다.

### iOS Google 로그인

`POST /api/auth/google`

```json
{
  "idToken": "GOOGLE_ID_TOKEN_FROM_IOS"
}
```

iOS Google Sign-In SDK에서 받은 ID Token을 전달합니다. 백엔드 설정과 Google Cloud 클라이언트 구성은
[`docs/GOOGLE_OAUTH.md`](docs/GOOGLE_OAUTH.md)를 참고하세요.

### 내 정보 조회

`GET /api/auth/me`

```http
Authorization: Bearer <accessToken>
```

### 회원탈퇴

`DELETE /api/auth/me`

```http
Authorization: Bearer <accessToken>
```

회원 계정과 해당 사용자의 Refresh Session 및 북마크를 삭제하며 성공 시 `204 No Content`를 반환합니다.

운영 환경에서는 `JWT_SECRET`을 32바이트 이상의 충분히 긴 무작위 값으로 반드시 설정하세요.

데이터까지 삭제할 때만 `docker compose down -v`를 사용합니다.

## 장소 API

홈 화면 "서울에서 뭐 하지?" 섹션용 API입니다. `Authorization: Bearer <accessToken>` 헤더는 목록·상세 조회에서는 선택이고(있으면 `bookmarked`가 사용자 기준으로 채워짐, 잘못된 토큰이면 401), 북마크 API에서는 필수입니다.

서버 시작 시 `places` 테이블이 비어 있으면 서울 샘플 장소 12개를 넣습니다. 끄려면 `SEED_PLACES_ENABLED=false`로 설정합니다.

### 카테고리 칩 목록

`GET /api/places/categories`

```json
[
  { "code": "ALL", "label": "전체" },
  { "code": "RESTAURANT", "label": "맛집" },
  { "code": "CAFE", "label": "카페" },
  { "code": "CULTURE", "label": "전시·문화" },
  { "code": "WALK", "label": "산책" },
  { "code": "EVENT", "label": "이벤트" }
]
```

### 장소 목록 (모두 보기)

`GET /api/places?category=WALK&curated=true&page=0&size=10`

- `category`: `ALL`(기본값), `RESTAURANT`, `CAFE`, `CULTURE`, `WALK`, `EVENT`. 그 외 값은 400.
- `curated`: 선택. `true`면 "추천 예시" 장소만.
- `page`: 0부터 시작(기본 0), `size`: 기본 10, 최대 50.
- 정렬: `displayOrder` 오름차순, 같으면 `id` 오름차순.

```json
{
  "content": [
    {
      "id": 1,
      "title": "북촌 골목 산책",
      "district": "종로구",
      "category": "WALK",
      "categoryLabel": "산책",
      "imageUrl": "https://picsum.photos/seed/bukchon/800/600",
      "curated": true,
      "bookmarked": false
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "hasNext": false
}
```

### 장소 상세

`GET /api/places/{id}`

목록 항목 필드에 `description`, `address`, `latitude`, `longitude`가 추가됩니다.

```json
{
  "id": 1,
  "title": "북촌 골목 산책",
  "district": "종로구",
  "category": "WALK",
  "categoryLabel": "산책",
  "imageUrl": "https://picsum.photos/seed/bukchon/800/600",
  "curated": true,
  "bookmarked": false,
  "description": "한옥 지붕 사이로 이어지는 골목을 천천히 걸으며 서울의 옛 정취를 느껴 보세요.",
  "address": "서울 종로구 계동길 37",
  "latitude": 37.5826,
  "longitude": 126.9837
}
```

없는 장소면 404:

```json
{ "code": "NOT_FOUND", "message": "장소를 찾을 수 없습니다.", "errors": {}, "timestamp": "..." }
```

### 북마크 추가 / 해제 (로그인 필요)

`PUT /api/places/{id}/bookmark` → `{ "placeId": 1, "bookmarked": true }`

`DELETE /api/places/{id}/bookmark` → `{ "placeId": 1, "bookmarked": false }`

여러 번 호출해도 결과가 같습니다(멱등). 토큰이 없거나 잘못되면 401, 장소가 없으면 404입니다.

### 내 북마크 목록 (로그인 필요)

`GET /api/places/bookmarks?page=0&size=10`

장소 목록과 같은 페이지 형식이며, 최근에 북마크한 순서로 정렬되고 `bookmarked`는 항상 `true`입니다.

### 오류 응답

| 상황 | 상태 | code |
| --- | --- | --- |
| 지원하지 않는 카테고리, 잘못된 page/size | 400 | `INVALID_REQUEST` |
| 토큰 없음/잘못된 토큰 | 401 | `UNAUTHORIZED` |
| 장소 없음 | 404 | `NOT_FOUND` |

## 코스(탐색하기) API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/courses/regions` | 지역 칩(성수·종로·홍대·여의도 등) |
| GET | `/api/courses?region=&lat=&lng=&page=&size=` | 기본 추천 코스 목록. `distanceMeters`=내 위치→첫 장소 |
| GET | `/api/courses/{id}?lat=&lng=` | 코스 상세: `stops`, `legs`(구간 거리·시간·`naverMapUrl`·`googleMapsUrl`), `startLeg`(현재 위치→1번), `missions` |
| POST | `/api/courses/recommendations` | `{prompt, lat?, lng?}` → AI 맞춤 코스 최대 3개(상세 형식). 로그인 필요, 10~30초 소요, 하루 20회 |
| POST | `/api/courses/{id}/refinements` | `{prompt, lat?, lng?}` → 기존 코스(기본/내 AI 코스)를 프롬프트대로 고친 **새 AI 코스 1개**(상세 형식). 원본 유지. 로그인 필요 |
| POST | `/api/courses/{id}/remaining-refinements` | `{prompt, completedThroughStopSeq, lat?, lng?}` → 완료 장소·미션을 보존하고 이후 일정만 수정한 새 코스. 로그인 필요 |
| PUT/DELETE | `/api/courses/{id}/save` | 코스 저장/해제 (로그인) |
| GET | `/api/courses/saved` | 저장한 코스 (로그인) |

- 출발지: 프롬프트에 "명지대 끝나고/○○에서 출발"처럼 출발점이 있으면 `origin`{name,address,lat,lng}으로 내려주고, `startLeg`는 출발지 → 1번 장소다. 출발지는 `stops`(노는 장소)에 들어가지 않는다. 출발지가 없으면 `startLeg`는 내 위치 → 1번 장소.
- 길찾기: `naverMapUrl`(`nmap://route/walk|public?...&appname=com.mju.hackathon.moment`)을 열고, 네이버지도 미설치 시 `googleMapsUrl`로 대체. iOS `Info.plist` `LSApplicationQueriesSchemes`에 `nmap` 추가 필요.
- 1.5km 이하 구간은 도보(`WALK`), 초과는 대중교통(`PUBLIC`). 거리·시간은 직선거리 기반 추정치.
- AI 코스는 만든 사람에게만 보인다. 숏폼 프로젝트 생성 시 `courseId`, `missionIds`를 넘기면 촬영 미션이 AI 편집 힌트로 전달된다.
- 오류: 503 `AI_UNAVAILABLE`, 502 `AI_NO_RESULT`(검증된 장소 부족), 429 `RATE_LIMITED`.

## 배포 (AWS)

`main`에 push하면 GitHub Actions가 빌드·테스트 → ECR 이미지 push → SSM으로 EC2에 배포한다.

- 주소: `https://3-39-47-78.sslip.io` (EC2 Elastic IP `3.39.47.78`, Caddy가 HTTPS 인증서 자동 발급)
- 리전 `ap-northeast-2`, EC2 `i-0804f2ce498c85ed3`(t3.small, Amazon Linux 2023), ECR `mju-hackathon-backend`
- EC2 안에서는 `/opt/app`에서 `docker compose -f docker-compose.prod.yml`로 app + PostgreSQL + Caddy가 돈다.
- 운영 비밀값은 EC2의 `/opt/app/.env`에 있다(Git에 없음). 수정 후 Actions에서 `CI/CD` 워크플로를 다시 실행하면 반영된다.
- 접속: `aws ssm start-session --target i-0804f2ce498c85ed3` (SSH 포트는 닫혀 있음)
- Google 로그인 운영 리디렉션 URI: `https://3-39-47-78.sslip.io/login/oauth2/code/google`
- GitHub Secrets: `AWS_ROLE_ARN`(OIDC 배포 역할), `EC2_INSTANCE_ID`
