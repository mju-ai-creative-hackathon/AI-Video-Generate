# 숏폼 제작 백엔드 연동

사용자 소스 업로드, 스타일·무드 선택, 제작 작업 등록, AI 서버 전달, 상태·결과 조회를 담당합니다.
영상 생성 자체는 외부 AI 서버의 역할입니다. 현재 AI 서버 명세는 **가안**이며, 담당자 명세 확정 시
`shortform.ai` 어댑터를 변경합니다. 기본 설정에서는 제작 요청에 503을 반환하고, 완료 영상이나
미리보기를 임의로 만들지 않습니다.

## 화면 흐름

1. 기존 로그인 API로 발급받은 Bearer 토큰을 준비합니다.
2. 옵션을 조회하고 제작 프로젝트를 생성합니다.
3. 사진·동영상을 업로드합니다. 각 파일은 해당 프로젝트 소유자만 조회할 수 있습니다.
4. 사용할 소스 5~20개를 원하는 순서대로 선택하고 스타일·무드를 선택합니다.
5. 제작 요청에 고유한 `Idempotency-Key` UUID를 보냅니다.
6. 제작 상태를 주기적으로 조회합니다. `SUCCEEDED`일 때 `videoUrl`로 결과를 재생합니다.

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
  "updatedAt":"2026-11-05T00:01:00Z"
}
```

`QUEUED` → `SUBMITTING` → `PROCESSING` → `SUCCEEDED` 또는 `FAILED`로 진행합니다.
프론트는 3~5초 간격으로 조회하고 완료·실패 시 조회를 중단합니다.
`FAILED`에는 사용자에게 표시할 안전한 오류 코드·메시지가 제공됩니다.
AI 서버의 원본 응답·내부 인증 키·서버 파일 경로는 노출하지 않습니다.
결과는 AI 측에서 제공한 URL을 전달하며 백엔드가 결과 영상을 다운로드하거나 별도로 보관하지 않습니다.
AI 측은 프론트에서 접근 가능한 HTTPS URL을 제공하고, 서명 URL의 수명·CORS 정책을 합의해야 합니다.

## AI 담당자용 가안

아직 확정된 계약이 아닙니다. HTTP 경로·필드·스타일·무드 코드·음악 ID는 협의 후 어댑터에서 조정합니다.
AI 생성 요청에 OpenAI API를 직접 호출하지 않습니다.

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
  "style":"CINEMATIC",
  "mood":"URBAN",
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
{"status":"PROCESSING","videoUrl":null,"thumbnailUrl":null}
```

완료 시 `status=SUCCEEDED`와 `videoUrl`이 필요하며, `thumbnailUrl`은 선택입니다.
업무 실패 시 `status=FAILED`를 반환합니다. 처리 중에는 `QUEUED` 또는 `PROCESSING`을 반환합니다.
콜백 URL이나 프론트엔드 액세스 토큰을 AI 서버에 전달하지 않습니다.

## 저장·운영

### 환경변수

| 이름 | 기본값 | 용도 |
| --- | --- | --- |
| `SHORTFORM_STORAGE_ROOT` | `./data/shortforms` | 비공개 원본 파일 저장 경로 |
| `SHORTFORM_AI_ENABLED` | `false` | 외부 AI 제작 연동 활성화 |
| `SHORTFORM_AI_BASE_URL` | 빈 값 | AI 서버 기본 URL. 예: `http://localhost:9000/api` |
| `SHORTFORM_AI_API_KEY` | 빈 값 | AI 서버에 보낼 선택적 Bearer 인증 키 |
| `SHORTFORM_WORKER_ENABLED` | `true` | 주기적인 제작 전달·상태 조회 활성화 |

`SHORTFORM_AI_ENABLED=true`와 유효한 `SHORTFORM_AI_BASE_URL`이 함께 있어야 제작 요청이 가능합니다.
빈 API 키는 인증 헤더를 보내지 않는다는 뜻이며, 실제 서버의 인증 계약에 따라 설정해야 합니다.
AI API 키는 프론트에 보내거나 Git에 커밋하지 않습니다.

추가 Spring 설정:

| 프로퍼티 | 기본값 | 범위·동작 |
| --- | --- | --- |
| `app.shortform.request-timeout-seconds` | `60` | 1~90초. AI 응답 본문을 전부 받는 시간까지 포함 |
| `app.shortform.render-timeout-seconds` | `1800` | 최소 120초. 작업 생성 시점부터 제작의 전체 제한 시간 |
| `app.shortform.max-failures` | `3` | 1~20. 연속 일시 오류가 이 횟수에 도달하면 실패 |
| `app.shortform.retry-delay-ms` | `10000` | 0 이상. 연속 실패 횟수에 비례해서 재시도 간격 증가 |
| `app.shortform.result-poll-delay-ms` | `5000` | 0 이상. 정상적인 처리 중 상태를 받은 후 다음 조회까지 간격 |
| `app.shortform.poll-delay-ms` | `3000` | 워커 주기 |
| `app.shortform.allow-insecure-result-urls` | `false` | 로컬 개발에서만 HTTP 결과 URL 허용 가능 |

작업을 가져간 워커의 임대는 고정 120초입니다. 외부 통신 중 DB 행 잠금을 유지하지 않습니다.
AI 응답 JSON은 최대 512KiB, 결과 URL은 최대 2000자입니다. 리다이렉트를 따라가지 않으므로
다른 서버로 AI 인증 헤더를 전달하지 않습니다.

업로드 파일은 비공개 로컬 저장소, 메타데이터와 제작 상태는 PostgreSQL에 보관합니다.
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
업로드·소유권·파일 제한·선택 순서·스타일 전달·멱등성·상태 전이·결과 URL 처리를 검증합니다.
AI 모델의 영상 품질과 실제 AI 서버 호환성은 담당자 API가 완성된 뒤 확인해야 합니다.
