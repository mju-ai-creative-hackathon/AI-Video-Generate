# 프론트 연동 가이드

미션별 사진·영상을 제출하면 서버가 스토리보드와 대본을 만들고, 사용자가 검토·승인하면 최종 영상을 생성한다.

- **기본 주소:** `http://<서버 IP>:8000/api/v1`
- **상세 명세:** 실행 중인 서버의 `/docs`, 또는 [openapi.json](openapi.json)
- **인증:** 모든 요청에 `X-API-Key: <키>` 헤더. 미디어 URL만 예외다(아래 참고).

## 전체 흐름

| 화면 | 호출 | 다음으로 넘어가는 조건 |
|---|---|---|
| 미션 선택 | `GET /missions` | 사용자가 미션 선택 |
| 자료 제출 | `POST /missions/{id}/runs` | `202`로 `job_id`, `run_id` 받음 |
| 생성 대기 | `GET /jobs/{job_id}` 반복 | `status`가 `done` |
| 검토·편집 | `GET /runs/{run_id}/storyboard`, `PUT` 같은 경로 | 사용자가 승인 버튼 누름 |
| 승인 | `POST /runs/{run_id}/approve` | `200` |
| 영상 생성 | `POST /runs/{run_id}/render` 후 `GET /jobs/{job_id}` 반복 | `status`가 `done` |
| 결과 재생 | `GET /runs/{run_id}`의 `video.url` | |

**승인 전에는 `render`가 `409`로 거부된다.** 승인 후 수정하면 새 초안이 되므로 다시 승인해야 한다.

## 자료 제출

`multipart/form-data`로 보낸다. **`files`의 순서가 그대로 컷 순서다.** 파일 하나가 컷 하나가 되고, 빠지는 파일은 없다.

| 필드 | 필수 | 내용 |
|---|---|---|
| `files` | 예 | 사진·영상. 여러 번 반복. 확장자와 제한은 `GET /options` |
| `items` | 아니요 | 파일별 정보의 JSON 문자열. 길이는 파일 수와 같아야 한다 |
| `format` | 아니요 | 포맷(템플릿) ID. 생략하면 자동 선택. 값은 `GET /options`의 `formats[].id` |
| `music` | 아니요 | 음악 ID. 생략하면 템플릿의 기본 음악. 값은 `GET /options`의 `music[].id` |

```js
const form = new FormData();
files.forEach((file) => form.append("files", file));
form.append("items", JSON.stringify([
  { note: "주문하는 장면이 꼭 들어가야 함" },
  { audio: "mix" },
]));
const res = await fetch(`${BASE}/missions/${missionId}/runs`, {
  method: "POST",
  headers: { "X-API-Key": KEY },
  body: form,
});
const { job_id, run_id } = await res.json();
```

`audio`는 영상 파일에만 의미가 있다.

| 값 | 동작 |
|---|---|
| `narration` | 기본값. 원본 소리를 빼고 AI 나레이션을 넣는다 |
| `source` | 원본 소리만 쓴다. 나레이션을 만들지 않는다 |
| `mix` | 원본 소리를 줄이고 나레이션을 얹는다 |

## 작업 조회

2~3초마다 `GET /jobs/{job_id}`를 호출한다. 새로고침한 뒤에는 `GET /runs/{run_id}`의 `active_job`으로 이어서 볼 수 있다.

| `status` | 처리 |
|---|---|
| `queued`, `running` | 계속 조회. `message`를 표시하고, `current`/`total`이 있으면 진행 막대에 쓴다 |
| `done` | 다음 단계로. `result`에 요약이 있다 |
| `failed` | `error`를 표시 |
| `needs_review` | 나레이션이 컷보다 길어 생성이 멈춤. 스토리보드를 다시 불러와 검토·승인 후 `render` |

`stage` 값: `preprocess`, `analyze`, `script`, `image`, `narration`, `video`, `compose`, `store`.

작업자는 하나이므로 다른 실행의 작업이 있으면 `queued`로 기다린다. 사진 컷은 AI 영상 생성에 컷마다 수 분 걸릴 수 있다.

## 스토리보드 편집

`GET /runs/{run_id}/storyboard` 응답에는 수정할 값과 표시용 값이 함께 있다. 저장할 때는 **아래 수정 항목만** 보낸다. 모르는 키가 있으면 `422`다.

```json
{
  "base_version": 3,
  "title": "우리 학교 분식 찾기",
  "cuts": [
    {
      "cut_id": "c_7f3a",
      "position": 1,
      "photo_id": "p01",
      "role": "hook",
      "narration": "우리 학교 최고의 분식은 어디일까?",
      "target_sec": 3.0,
      "motion_prompt": "Close-up of ...",
      "effect": null,
      "source_clip": null,
      "locked": ["narration"],
      "shot": {
        "shot_size": "close_up",
        "camera_move": "slow_push_in",
        "subject_action": "김이 피어오름",
        "crop_choice": 1,
        "on_screen_text": "분식 찾기",
        "transition_in": "hard_cut"
      }
    }
  ]
}
```

| 규칙 | 내용 |
|---|---|
| `base_version` | 응답의 `version`을 그대로 보낸다. 최신이 아니면 `409`와 변경 내역을 돌려준다 |
| 저장에서 빼는 값 | 컷의 `media`, `framing_refs`, `slot`, `fit_score`, `crop_source`, `flags`, `shot.crop_options` |
| `crop_box` | 사용자가 위치를 직접 바꿨을 때만 보낸다. 후보 선택은 `crop_choice`(1부터)만 보낸다 |
| 순서 변경 | `position`을 바꾼다. `cut_id`는 그대로 둔다 |
| 컷 삭제·추가 | 목록에서 빼면 삭제. `cut_id`를 비우면 새 컷 |
| 사진·영상 교체 | `photo_id`를 `photos` 목록의 값으로 바꾼다. 사진으로 바꿀 때는 `source_clip`을 `null`로 보낸다 |
| `locked` | AI 재생성에서 제외할 항목. 값은 `GET /options` |

**음악:** 응답의 `audio_mode`가 `music`이면 음악 전용 템플릿이다. 나레이션과 TTS 없이 음악만 넣고, 컷 길이는 `target_sec`(템플릿 값)을 그대로 쓴다. 저장 본문에 `"music_id": "<id>"`를 넣으면 음악을 바꾸고, 빼면 그대로 두며, `null`이면 뺀다. 음악 전용인데 음악이 없거나 파일이 없으면 승인이 `400`으로 거부된다. 나레이션 템플릿에 음악을 넣으면 나레이션 구간에서 작아지는 배경 음악이 된다.

**영상 컷**은 `source_clip`으로 구간과 소리를 정한다. `target_sec`은 서버가 구간 길이로 맞추며, `motion_prompt`와 `effect`는 쓰지 않는다.

```json
"source_clip": { "in_sec": 1.5, "out_sec": 5.0, "audio": "narration" }
```

`media.suggested_segment`가 AI가 고른 구간이다. 미리보기는 `media.video_url`을 쓰면 된다.

**크롭 표시:** `shot.crop_options`의 각 항목은 원본 대비 0~1의 `[x1, y1, x2, y2]`다. `media.image_url` 위에 사각형으로 그릴 수 있다. 영상 컷의 이미지는 고른 구간의 대표 프레임이다.

**재생성:** `POST /runs/{id}/storyboard/regen`에 `cut_ids`, `fields`를 보내면 작업이 생긴다. 끝나면 스토리보드를 다시 불러온다. `result.skipped_locked`에 잠겨서 건너뛴 항목이 있다.

## 승인과 경고

`POST /runs/{id}/approve`

```json
{ "version": 4, "accept_warnings": false }
```

경고가 있으면 `409 warnings_not_accepted`와 경고 목록이 온다. 사용자에게 보여주고 확인을 받으면 `accept_warnings: true`로 다시 보낸다. 승인은 최신 버전만 가능하다.

## 영상 생성과 재생

`POST /runs/{id}/render`의 본문은 생략할 수 있다. 시험할 때는 요금 없이 돌리는 값을 쓴다.

```json
{ "tts": "silent", "video": "kenburns" }
```

완료 후 `GET /runs/{id}`의 `video.url`을 `<video src>`에 넣는다. 탐색(Range 요청)을 지원한다. `result.fallback_cuts`에 있는 컷은 AI 생성에 실패해 Ken Burns로 대체된 것이다. `POST /runs/{id}/cuts/{cut_id}/regen-clip`으로 그 컷만 다시 만들 수 있다.

## 미디어 URL

`image_url`, `video_url`, `video.url`은 서명이 들어 있어 인증 헤더 없이 쓸 수 있다. **서버 주소 없는 경로로 오므로 기본 주소의 호스트를 앞에 붙인다.** 기본 1시간 뒤 만료되며(`401`), 스토리보드나 실행을 다시 조회하면 새 URL을 받는다.

## 오류 형식

```json
{ "error": { "code": "stale_base_version", "message": "...", "changes": [] } }
```

| 코드 | HTTP | 처리 |
|---|---|---|
| `invalid_request` | 422 | 요청 형식 오류. `issues` 표시 |
| `storyboard_invalid` | 422 | `issues`의 `location`으로 해당 항목 표시 |
| `stale_base_version` | 409 | 최신 스토리보드를 다시 불러오라고 안내. `changes` 표시 |
| `warnings_not_accepted` | 409 | 경고 확인 후 재요청 |
| `approval_required` | 409 | 승인 화면으로 이동 |
| `job_in_progress` | 409 | 작업이 끝난 뒤 재시도. 생성 중에는 수정과 승인도 막힌다 |
| `not_found` | 404 | |
| `upload_too_large` | 413 | |
| `unsupported_media` | 415 | |
| `bad_request` | 400 | `message` 표시 |

API 키 오류와 만료된 URL은 `401`이며, 형식이 `{ "detail": "..." }`이다.

## Backend 연동

iOS 앱은 이 서버를 직접 부르지 않고 Backend(`mju-ai-creative-hackathon/Backend`)만 호출한다. Backend 워커가 아래 경로로 소스를 넘기고 상태를 조회하며, 검토 API는 위의 `/runs/...` 경로를 그대로 전달한다. 앱 개발자는 Backend의 `docs/SHORTFORM_API.md`를 보면 된다. 인증은 `X-API-Key` 대신 `Authorization: Bearer <API_KEY>`도 받는다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/health` | 제작 가능 여부 확인 |
| GET | `/catalog` | 템플릿 목록(`audio_mode`, 기본 음악 포함)과 파일이 있는 음악 목록 |
| POST | `/renders` | `manifest`(JSON)와 `files`. `Idempotency-Key`(Backend 작업 UUID)의 32자 hex가 실행 ID다. 같은 키는 같은 실행을 돌려준다 |
| GET | `/renders/{run_id}` | Backend 계약의 상태(`status`, `phase`, `busy`, 진행, `storyboardVersion`, `error`) |
| GET | `/renders/{run_id}/video` | 완성본. Backend가 받아 보관한다 |

- 실행마다 미션 하나를 만들고 설명에 제목, 스타일·무드, 코스 촬영 미션(`shootingHints`)을 순서대로 적는다. 어떤 파일이 어떤 미션인지는 알 수 없어 파일별 메모로는 넣지 않는다.
- `manifest`의 `formatId`(템플릿)와 `musicId`(음악)는 선택이다. 없는 값이면 `422`로 거부해 작업을 만들지 않는다.
- 상태는 실행의 최근 작업으로 정한다. 생성이 실패하거나 나레이션이 길어 멈추면 `AWAITING_REVIEW`와 `error`로 돌아가, 사용자가 고쳐서 다시 승인할 수 있다. 예외 원문은 서버 경로가 들어갈 수 있어 보내지 않는다.
- 미디어 경로(`/runs/{id}/media/...`)는 서명 대신 API 키 헤더로도 열린다. Backend가 앱에 자기 서명 URL을 주고 이 경로를 대신 호출한다.
