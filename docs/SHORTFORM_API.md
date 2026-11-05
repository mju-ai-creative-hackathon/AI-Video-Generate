# 숏폼 제작 백엔드 연동

사용자 소스 업로드, 스타일·무드 선택, 제작 작업 등록, AI 서버 전달, **스토리보드 검토·승인 전달**,
상태·결과 조회를 담당합니다. 영상 생성 자체는 AI 서버(`AI-Video-Generate` 레포의 `vlog serve`)의 역할입니다.
기본 설정에서는 제작 요청에 503을 반환하고, 완료 영상이나 미리보기를 임의로 만들지 않습니다.

**AI는 사용자가 스토리보드와 대본을 검토·승인하기 전에는 TTS·영상 생성을 시작하지 않습니다.**

## 화면 흐름

1. 기존 로그인 API로 발급받은 Bearer 토큰을 준비합니다.
2. 옵션을 조회하고 제작 프로젝트를 생성합니다.
3. 사진·동영상을 업로드합니다. 각 파일은 해당 프로젝트 소유자만 조회할 수 있습니다.
4. 사용할 소스 5~20개를 원하는 순서대로 선택하고 스타일·무드를 선택합니다. **이 순서가 컷 순서입니다.**
5. 제작 요청에 고유한 `Idempotency-Key` UUID를 보냅니다.
6. 제작 상태를 주기적으로 조회합니다. `phase`로 화면을 나눕니다.

| `status` | `phase` | 화면 |
| --- | --- | --- |
| `QUEUED`, `SUBMITTING`, `PROCESSING` | `DRAFTING` | 초안 생성 대기. `progress.message` 표시 |
| `PROCESSING` | `AWAITING_REVIEW` | **검토 화면.** 스토리보드 조회·수정·재생성 후 승인 |
| `PROCESSING` | `RENDERING` | 영상 생성 대기. 사진 컷마다 수 분 걸릴 수 있음 |
| `SUCCEEDED` | `DONE` | `videoUrl` 재생 |
| `FAILED` | 아무 값 | `errorMessage` 표시 |

검토 중에도 `status`는 `PROCESSING`입니다. 기존 앱은 `phase`를 모르므로 검토 단계에서 계속 기다리게 됩니다.
검토 화면이 있는 앱을 배포한 뒤 운영에서 `SHORTFORM_AI_ENABLED=true`로 켭니다.

## 프론트엔드 API

기본 경로는 `/api/shortforms`입니다. 옵션 조회 외에는 `Authorization: Bearer <accessToken>`이 필요합니다.
다른 사용자의 프로젝트·소스·제작 작업 ID를 전달하면 404를 반환합니다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/options` | 스타일·무드·제작 가능 여부 조회 |
| POST | `/` | 프로젝트 생성, 201 |
| GET | `/?page=0&size=20` | 본인 프로젝트 목록 |
| GET | `/{projectId}` | 소스와 최신 제작 상태를 포함한 프로젝트 조회 |
| POST | `/{projectId}/assets` | multipart 파일 업로드, 201 |
| DELETE | `/{projectId}/assets/{assetId}` | 제작 요청 전 소스 삭제, 204 |
| GET | `/{projectId}/assets/{assetId}/content` | 인증된 원본 소스 조회 |
| POST | `/{projectId}/renders` | 제작 작업 등록, 202 |
| GET | `/{projectId}/renders/{renderId}` | 제작 상태와 결과 조회 |
| GET | `/{projectId}/renders/{renderId}/storyboard?version=` | 검토할 스토리보드·대본 |
| PUT | `/{projectId}/renders/{renderId}/storyboard` | 수정본 저장(새 버전) |
| POST | `/{projectId}/renders/{renderId}/storyboard/regen` | 지정 컷·항목 AI 재생성, 202 |
| POST | `/{projectId}/renders/{renderId}/approve` | 승인하고 영상 생성 시작 |
| GET | `/{projectId}/renders/{renderId}/media/{photoId}[/poster]` | 서명 URL. 검토용 원본·대표 이미지 |
| GET | `/{projectId}/renders/{renderId}/video` | 서명 URL. 완성본(탐색 지원) |

### 프로젝트 생성

```http
POST /api/shortforms
Authorization: Bearer <accessToken>
Content-Type: application/json

{"title":"서울숲에서 보낸 하루"}
```

생성 응답의 `id`가 이후 사용하는 `projectId`입니다. 프로젝트 상세에는 `assets`, `latestRender`,
`createdAt`, `courseId`, `missionIds`를 포함합니다.

여행 코스의 촬영 미션으로 만드는 숏폼이면 선택적으로 코스와 미션을 연결합니다.

```json
{"title":"서울숲 코스 브이로그","courseId":12,"missionIds":[101,102,103]}
```

- `courseId`, `missionIds`는 모두 선택입니다. `title`만 보내는 기존 요청은 그대로 동작합니다.
- `missionIds`는 최대 20개, 중복은 첫 순서만 남깁니다. `missionIds`만 보내고 `courseId`가 없으면 400입니다.
- 연결하지 않은 프로젝트의 응답은 `courseId: null`, `missionIds: []`입니다.
- 연결 정보는 생성 후 변경할 수 없습니다. 제작 시 AI 요청의 `shootingHints`로 활용합니다.

### 소스 업로드

`multipart/form-data`의 반복되는 `files` 필드에 파일을 전달합니다.
미션 연결 정보를 보관하려면 선택적으로 `missionId` UUID를 전달합니다.
미션 도메인이 아직 없으므로 실제 미션 존재 여부를 검증하거나 미션 완료 처리하지 않습니다.

```bash
curl -X POST 'http://localhost:8080/api/shortforms/PROJECT_ID/assets' \
  -H 'Authorization: Bearer ACCESS_TOKEN' \
  -F 'files=@photo-01.jpg' -F 'files=@clip-01.mp4'
```

- 사진: JPEG, PNG, WebP, 파일당 최대 10MiB
- 동영상: MP4, MOV, WebM, 파일당 최대 100MiB
- 프로젝트당 최대 20개. 제작 요청 시 5~20개 선택
- 단일 업로드 요청의 합계는 최대 500MB이며, 큰 파일은 여러 요청으로 나눠 올립니다.
- 파일의 선언 MIME 유형과 시그니처를 확인합니다. 영상 디코딩·재생 길이·화질 검사는 수행하지 않습니다.
- 응답 배열의 항목: `id`, `originalFilename`, `contentType`, `sizeBytes`, `kind`, `missionId`, `contentUrl`
- 파일명은 표시용이며 서버 저장 경로는 생성한 식별자를 사용합니다.
- `contentUrl`은 인증된 상대 경로입니다. 브라우저 `<img>`나 `<video>`에서 바로 JWT 헤더를 붙일 수 없다면
  인증된 `fetch`로 Blob을 받은 뒤 `URL.createObjectURL`로 미리보기를 만듭니다.
- 첫 제작 요청 이후에는 소스를 추가·삭제할 수 없습니다. 다른 소스를 사용하려면 프로젝트를 새로 만듭니다.

### 제작 옵션

`GET /api/shortforms/options` 응답의 `renderingAvailable`로 제작 버튼 활성화 여부를 판단합니다.
스타일은 `CINEMATIC`, `DYNAMIC`, `VLOG`, `MINIMAL`, 무드는 `URBAN`, `ROMANTIC`, `CALM`, `ENERGETIC`입니다.
옵션은 AI 담당자와 협의하기 위한 코드이며 실제 효과·배경 음악·미리보기 파일을 포함하지 않습니다.

### 제작 요청

```http
POST /api/shortforms/PROJECT_ID/renders
Authorization: Bearer <accessToken>
Idempotency-Key: 9593e411-5ef2-4db8-9d2b-66fe65750bb0
Content-Type: application/json

{
  "style":"CINEMATIC",
  "mood":"URBAN",
  "musicId":null,
  "assetIds":["UUID_1","UUID_2","UUID_3","UUID_4","UUID_5"]
}
```

`assetIds` 배열 순서를 편집 순서로 전달합니다. 이 프로젝트에 속한 소스만 선택할 수 있으며 중복 ID는 거절합니다.
`musicId`는 선택적 AI 측 음악 식별자입니다. 백엔드가 음악을 다운로드하거나 저작권을 검증하지 않습니다.
음악 목록·실제 사용 가능한 식별자는 AI 담당자와 추가 합의해야 합니다.

같은 프로젝트에서 같은 키·같은 요청을 다시 보내면 기존 제작 작업을 반환합니다.
같은 키를 다른 요청에 재사용하거나 진행 중인 다른 작업이 있으면 409입니다.
타임아웃·통신 재시도 시 프론트는 같은 키를 유지해야 합니다.
완료·실패 후 새 제작은 새로운 키로 요청할 수 있습니다.

### 상태와 결과

```json
{
  "id":"RENDER_UUID",
  "projectId":"PROJECT_UUID",
  "status":"SUCCEEDED",
  "style":"CINEMATIC",
  "mood":"URBAN",
  "musicId":null,
  "videoUrl":"https://media.example.com/result.mp4",
  "thumbnailUrl":"https://media.example.com/result.jpg",
  "errorCode":null,
  "errorMessage":null,
  "createdAt":"2026-11-05T00:00:00Z",
  "updatedAt":"2026-11-05T00:01:00Z",
  "phase":"DONE",
  "reviewBusy":false,
  "progress":null,
  "storyboardVersion":2,
  "reviewError":null
}
```

`QUEUED` → `SUBMITTING` → `PROCESSING` → `SUCCEEDED` 또는 `FAILED`로 진행합니다.
프론트는 3~5초 간격으로 조회하고 완료·실패 시 조회를 중단합니다.
`FAILED`에는 사용자에게 표시할 안전한 오류 코드·메시지가 제공됩니다.
AI 서버의 원본 응답·내부 인증 키·서버 파일 경로는 노출하지 않습니다.

- `progress`: `{stage, message, current, total}`. `current`/`total`이 있으면 진행 막대에 씁니다. 없으면 `null`.
- `reviewBusy`: 검토 중 재생성이 도는 중이면 `true`. 끝나면 `storyboardVersion`이 올라갑니다.
- `reviewError`: 승인 후 생성이 멈춰 검토 단계로 돌아온 이유. 예: 나레이션이 컷보다 길어 AI가 줄인 초안을 만듦.
  스토리보드를 다시 불러와 확인하고 다시 승인하면 끝난 컷은 재사용해 이어서 만듭니다.
- `videoUrl`: 백엔드가 보관한 완성본의 **서명 URL**이며 인증 헤더 없이 재생·다운로드할 수 있습니다.
  기본 1시간 뒤 만료(`401`)되므로, 만료되면 제작 상태를 다시 조회해 새 URL을 받습니다. 탐색(Range)을 지원합니다.

### 스토리보드 검토

`phase=AWAITING_REVIEW`일 때만 수정·재생성·승인할 수 있고, 그 외에는 `409 NOT_IN_REVIEW`입니다.
조회는 초안이 생긴 뒤 언제든 가능합니다. 본문 형식은 AI 서버 명세를 그대로 씁니다
(`AI-Video-Generate` 레포 `docs/api.md`의 "스토리보드 편집"). 키 이름은 snake_case입니다.

```http
GET /api/shortforms/PROJECT_ID/renders/RENDER_ID/storyboard
Authorization: Bearer <accessToken>
```

응답의 `version`, `title`, `cuts[]`(컷 순서, 역할, 나레이션, 화면 문구, 샷 설계, 영상 구간 `source_clip`),
`photos[]`, `warnings`를 화면에 씁니다. `cuts[].media.image_url`, `video_url`은 이 서버의 서명 URL로 바뀌어
있어 `<img>`·AVPlayer에 바로 넣을 수 있습니다. 1시간 뒤 만료되면 스토리보드를 다시 조회합니다.

```http
PUT /api/shortforms/PROJECT_ID/renders/RENDER_ID/storyboard
Content-Type: application/json

{"base_version":1,"title":"서울숲에서 보낸 하루","cuts":[{"cut_id":"c_7f3a","position":1, "...":"..."}]}
```

저장하면 새 버전이 생깁니다. 응답은 `{"version":2,"changed":true,"changes":[...],"warnings":[...]}`입니다.

```http
POST /api/shortforms/PROJECT_ID/renders/RENDER_ID/storyboard/regen
Content-Type: application/json

{"cut_ids":["c_7f3a"],"fields":["narration"]}
```

`202`와 제작 상태를 돌려주며 `reviewBusy=true`가 됩니다. 제작 상태를 조회해 `reviewBusy=false`가 되고
`storyboardVersion`이 올라가면 스토리보드를 다시 불러옵니다.

```http
POST /api/shortforms/PROJECT_ID/renders/RENDER_ID/approve
Content-Type: application/json

{"version":2,"acceptWarnings":false}
```

승인과 영상 생성 시작을 한 번에 합니다. 성공하면 `phase=RENDERING`인 제작 상태를 돌려줍니다.
중간에 통신이 끊기면 같은 요청을 다시 보내면 됩니다(승인과 생성 시작 모두 중복 실행되지 않습니다).

검토 오류는 공통 오류 형식에 `details`가 붙습니다.

```json
{"code":"WARNINGS_NOT_ACCEPTED","message":"경고를 확인한 뒤 ...","errors":{},"timestamp":"...",
 "details":{"warnings":[{"location":"cuts[2].narration","code":"narration_long","message":"..."}]}}
```

| 코드 | HTTP | 처리 |
| --- | --- | --- |
| `STALE_BASE_VERSION` | 409 | 최신 스토리보드를 다시 불러오라고 안내. `details.changes` 표시 |
| `WARNINGS_NOT_ACCEPTED` | 409 | `details.warnings`를 보여주고 확인받으면 `acceptWarnings:true`로 재요청 |
| `JOB_IN_PROGRESS` | 409 | 재생성이 끝난 뒤 다시 시도 |
| `NOT_IN_REVIEW`, `STORYBOARD_NOT_READY` | 409 | 제작 상태를 다시 조회 |
| `STORYBOARD_INVALID` | 422 | `details.issues`의 `location`으로 해당 항목 표시 |
| `INVALID_REQUEST` | 422 | 요청 형식 오류. `details.issues` 표시 |
| `RENDER_LIMIT_EXCEEDED` | 429 | 하루 제작 요청 수 초과 |
| `AI_UNAVAILABLE` | 503 | AI 서버 PC가 꺼져 있거나 연결이 끊김. 잠시 후 재시도 |
| `AI_BAD_GATEWAY` | 502 | AI 서버 설정 오류. 운영자 확인 필요 |

## AI 서버 계약

`AI-Video-Generate` 레포의 `vlog serve`가 구현합니다(`pipeline/api/bridge.py`). 기본 URL은
`SHORTFORM_AI_BASE_URL`(예: `http://host.docker.internal:18000/api/v1`), 인증은 `Authorization: Bearer <SHORTFORM_AI_API_KEY>`입니다.
AI 생성 요청에 OpenAI API를 직접 호출하지 않습니다.

| 메서드 | AI 서버 경로 | 백엔드가 쓰는 곳 |
| --- | --- | --- |
| GET | `/health` | `options.renderingAvailable` (30초 캐시) |
| POST | `/renders` | 워커: 소스 제출, 초안 생성 시작 |
| GET | `/renders/{jobId}` | 워커: 상태 조회(검토 중 포함) |
| GET | `/renders/{jobId}/video` | 워커: 완성본 내려받기 |
| GET, PUT | `/runs/{jobId}/storyboard` | 검토 API 전달 |
| POST | `/runs/{jobId}/storyboard/regen` | 검토 API 전달 |
| POST | `/runs/{jobId}/approve`, `/runs/{jobId}/render` | 승인 API 전달 |
| GET | `/runs/{jobId}/media/{photoId}[/poster]` | 서명 미디어 URL 전달 |

### 생성 등록

```http
POST {SHORTFORM_AI_BASE_URL}/renders
Authorization: Bearer <optional SHORTFORM_AI_API_KEY>
Idempotency-Key: <backend render UUID>
Content-Type: multipart/form-data; boundary=...
```

multipart 필드:

- `manifest`: `application/json`, 아래 메타데이터
- `files`: 원본 파일 Resource, 소스 개수만큼 반복

```json
{
  "jobId":"BACKEND_RENDER_UUID",
  "projectId":"PROJECT_UUID",
  "title":"서울숲에서 보낸 하루",
  "style":"CINEMATIC",
  "styleLabel":"시네마틱",
  "mood":"URBAN",
  "moodLabel":"도시적인",
  "musicId":null,
  "sources":[
    {"assetId":"ASSET_UUID","missionId":null,"position":0,"contentType":"image/jpeg","filename":"generated-name.jpg"}
  ]
}
```

`shootingHints`는 선택 필드입니다. 코스와 연결된 프로젝트에서 촬영 미션 정보를 찾은 경우에만 포함하며,
없으면 필드 자체를 보내지 않습니다. AI 서버는 이 필드가 없어도 기존과 동일하게 처리해야 합니다.

```json
"shootingHints":[
  {"missionId":101,"placeName":"서울숲","kind":"VIDEO","title":"입구에서 걸어 들어가기","guide":"정면에서 천천히 걸으며 촬영","durationSec":5},
  {"missionId":102,"placeName":"서울숲","kind":"PHOTO","title":"사슴 방사장","guide":"가로 구도","durationSec":null}
]
```

- `kind`: `PHOTO` 또는 `VIDEO`. `durationSec`은 영상 미션의 권장 길이(초)이며 없을 수 있습니다.
- 최대 20개, 프로젝트 생성 시 지정한 미션 순서입니다. 힌트 조회에 실패하면 힌트 없이 제작을 진행합니다.
- 현재 `sources[].missionId`(업로드 시 UUID)와 `shootingHints[].missionId`(코스 미션 숫자 ID)는 별개 값입니다.

AI 서버는 `Idempotency-Key`를 기준으로 중복 제출을 같은 작업으로 처리해야 합니다.
백엔드가 재시작되거나 제출 응답 저장에 실패하면 같은 키로 재제출할 수 있습니다.
동일 키로 여러 영상을 생성하거나 비용을 중복 청구하면 안 됩니다.

```json
{"jobId":"ai-job-001"}
```

AI 작업 ID는 영문·숫자·밑줄·하이픈 1~128자입니다.

### 제작 상태 조회

```http
GET {SHORTFORM_AI_BASE_URL}/renders/ai-job-001
Authorization: Bearer <optional SHORTFORM_AI_API_KEY>
```

```json
{"status":"AWAITING_REVIEW","phase":"AWAITING_REVIEW","busy":false,"stage":null,"message":null,
 "current":null,"total":null,"storyboardVersion":1,"error":null}
```

- `status`: `QUEUED`, `PROCESSING`, `AWAITING_REVIEW`, `SUCCEEDED`, `FAILED`.
- `phase`: `DRAFTING`, `AWAITING_REVIEW`, `RENDERING`, `DONE`. 처리 중의 단계를 구분합니다.
- 완료 시 `status=SUCCEEDED`이고 `videoUrl`은 없습니다. 백엔드가 `/renders/{jobId}/video`에서 받아 보관합니다.
  (`videoUrl`을 주는 구 계약 서버도 그대로 지원하며, 이때는 HTTPS URL이어야 합니다.)
- 업무 실패 시 `status=FAILED`를 반환합니다. 생성 실패처럼 사람이 고쳐서 다시 승인할 수 있는 경우는 `AWAITING_REVIEW`와 `error`입니다.

콜백 URL이나 프론트엔드 액세스 토큰을 AI 서버에 전달하지 않습니다.

## 저장·운영

### 환경변수

| 이름 | 기본값 | 용도 |
| --- | --- | --- |
| `SHORTFORM_STORAGE_ROOT` | `./data/shortforms` | 비공개 원본 파일 저장 경로 |
| `SHORTFORM_AI_ENABLED` | `false` | 외부 AI 제작 연동 활성화 |
| `SHORTFORM_AI_BASE_URL` | 빈 값 | AI 서버 기본 URL. 예: `http://host.docker.internal:18000/api/v1` |
| `SHORTFORM_AI_API_KEY` | 빈 값 | AI 서버에 보낼 Bearer 인증 키. AI 서버 `.env`의 `API_KEY`와 같은 값 |
| `SHORTFORM_WORKER_ENABLED` | `true` | 주기적인 제작 전달·상태 조회 활성화 |
| `SHORTFORM_SUBMIT_TIMEOUT_SECONDS` | `300` | 소스 업로드를 포함한 제출 요청 제한 시간 |
| `SHORTFORM_DRAFT_TIMEOUT_SECONDS` | `1800` | 제작 요청부터 초안 생성까지 제한 시간 |
| `SHORTFORM_RENDER_TIMEOUT_SECONDS` | `7200` | 승인부터 영상 완성까지 제한 시간 |
| `SHORTFORM_MAX_FAILURES` | `3` | 연속 일시 오류 허용 횟수(1~100). 검토 중에는 세지 않음 |
| `SHORTFORM_DAILY_RENDER_LIMIT` | `5` | 사용자별 하루 제작 요청 수. `0`이면 제한 없음 |
| `SHORTFORM_MEDIA_URL_TTL_SECONDS` | `3600` | 결과 영상·검토 미디어 서명 URL 유효 시간 |

서명 URL은 요청 주소로 절대 URL을 만듭니다. 리버스 프록시 뒤에서는 `SERVER_FORWARD_HEADERS_STRATEGY=framework`가
필요합니다(운영 compose에 있음). 서명 키는 `JWT_SECRET`에서 만들므로 바꾸면 이전 URL이 무효가 됩니다.

`SHORTFORM_AI_ENABLED=true`와 유효한 `SHORTFORM_AI_BASE_URL`이 함께 있어야 제작 요청이 가능합니다.
빈 API 키는 인증 헤더를 보내지 않는다는 뜻이며, 실제 서버의 인증 계약에 따라 설정해야 합니다.
AI API 키는 프론트에 보내거나 Git에 커밋하지 않습니다.

추가 Spring 설정:

| 프로퍼티 | 기본값 | 범위·동작 |
| --- | --- | --- |
| `app.shortform.request-timeout-seconds` | `60` | 1~90초. AI 응답 본문을 전부 받는 시간까지 포함 |
| `app.shortform.retry-delay-ms` | `10000` | 0 이상. 연속 실패 횟수에 비례해서 재시도 간격 증가 |
| `app.shortform.result-poll-delay-ms` | `5000` | 0 이상. 정상적인 처리 중 상태를 받은 후 다음 조회까지 간격 |
| `app.shortform.poll-delay-ms` | `3000` | 워커 주기 |
| `app.shortform.allow-insecure-result-urls` | `false` | 로컬 개발에서만 HTTP 결과 URL 허용 가능 |

작업을 가져간 워커의 임대는 고정 120초입니다. 외부 통신 중 DB 행 잠금을 유지하지 않습니다.
AI 응답 JSON은 최대 512KiB, 결과 URL은 최대 2000자입니다. 리다이렉트를 따라가지 않으므로
다른 서버로 AI 인증 헤더를 전달하지 않습니다.

업로드 파일과 완성본은 비공개 로컬 저장소, 메타데이터와 제작 상태는 PostgreSQL에 보관합니다.
완성본은 워커가 트랜잭션 밖에서 내려받아 저장한 뒤 성공 처리하며, 저장 중 연결이 끊기면 다시 받습니다.
서버 교체 시 파일을 잃지 않도록 운영 Docker 볼륨을 함께 사용해야 합니다.
이 구성은 단일 서버 파일 저장소 기준입니다. 다중 서버 배포는 공유 스토리지나 S3 어댑터가 필요합니다.
제작 요청은 영속 작업으로 저장하고 워커가 비동기로 전달·조회합니다.
프로세스가 중단된 경우 임대 시간이 지난 작업을 다시 처리하며, AI 서버 멱등 처리가 이를 보완합니다.
일시적인 연결 오류·408·429·5xx는 재시도하고, 잘못된 응답·그 외 4xx·제작 실패는 실패 처리합니다.
무한 대기하지 않도록 작업 제한 시간이 적용됩니다.
자동 소스 보관 기간·사용자별 전체 저장 용량·완료 영상 장기 보관 정책은 별도 제품 정책이 필요합니다.
프로젝트 전체 삭제 API는 현재 포함하지 않습니다. 첫 제작 요청 이후 원본 삭제·교체도 제한되므로,
운영 전 사용자 삭제 요청과 보관 기간에 맞춘 정리 정책을 추가해야 합니다.

## 검증 범위

통합 테스트는 실제 PostgreSQL Testcontainer와 로컬 HTTP 가짜 AI 서버를 사용합니다.
가짜 서버는 테스트에만 있으며 운영 환경에서 성공한 영상을 가장하지 않습니다.
업로드·소유권·파일 제한·선택 순서·스타일 전달·멱등성·상태 전이·결과 URL 처리,
승인 전 생성 요청 거부, 검토 오류 전달, 미디어 URL 서명, 완성본 보관을 검증합니다.
AI 모델의 영상 품질과 실제 AI 서버와의 연결은 배포 환경에서 확인해야 합니다.
