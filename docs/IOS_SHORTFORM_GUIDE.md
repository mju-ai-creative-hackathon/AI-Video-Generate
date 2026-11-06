# iOS 숏폼 연동 가이드

iOS 앱(`hackathon-iOS`)이 Backend와 숏폼을 만들고, 검토·편집하고, 결과를 보여주는 데 필요한 호출만 모았다.
전체 명세와 오류 코드는 [SHORTFORM_API.md](SHORTFORM_API.md)를 본다.

- 기본 경로: `https://api.parkserver.xyz/api/shortforms`
- 인증: `options`와 서명 URL을 빼고 모두 `Authorization: Bearer <accessToken>`
- 앱은 Backend만 호출한다. AI 서버(LLM·영상 생성 PC)는 앱에서 보이지 않는다.

## 전체 흐름

| 화면 | 호출 | 다음으로 넘어가는 조건 |
|---|---|---|
| 스타일 선택 | `GET /options` | `renderingAvailable == true`. `templates`, `musics`로 선택지 구성 |
| 프로젝트 생성 | `POST /` | 201, `id`가 `projectId` |
| 사진·영상 선택 | `POST /{projectId}/assets` (파일 하나씩) | 5~20개 업로드 완료 |
| 제작 요청 | `POST /{projectId}/renders` + `Idempotency-Key` | 202, `id`가 `renderId` |
| 초안 대기 | `GET /{projectId}/renders/{renderId}` 3~5초마다 | `phase == "AWAITING_REVIEW"` |
| 검토·편집 | `GET`/`PUT .../storyboard`, `POST .../storyboard/regen` | 사용자가 승인 |
| 승인 | `POST .../approve` | 200, `phase == "RENDERING"` |
| 생성 대기 | `GET /{projectId}/renders/{renderId}` 3~5초마다 | `status == "SUCCEEDED"` |
| 결과 재생 | 응답의 `videoUrl` | |
| 마이 화면 | `GET /videos` | |

## 1. 선택지

```http
GET /api/shortforms/options
```

```json
{
  "styles":[{"code":"VLOG","label":"브이로그"}], "moods":[{"code":"CALM","label":"차분한"}],
  "minAssets":5, "maxAssets":20, "maxImageBytes":10485760, "maxVideoBytes":104857600,
  "renderingAvailable":true,
  "templates":[{"code":"reel_mood_trip_temp_v1","label":"감성 여행 몽타주","hookType":"장소 제시형",
                "audioMode":"MUSIC","defaultMusicId":"roi_instrumental_demo"}],
  "musics":[{"code":"roi_instrumental_demo","label":"Roi (Instrumental) [시연용]","moods":["잔잔함","설렘"]}]
}
```

- `renderingAvailable == false`면 AI 서버가 꺼져 있다. 제작 버튼을 막는다.
- `templates`가 비어 있으면 AI 서버에 닿지 않은 것이다. "AI 추천"(템플릿 자동 선택)만 보여준다.
- `audioMode`: `NARRATION`은 AI 나레이션과 자막, `MUSIC`은 나레이션 없이 음악만 넣는다.
- 목록은 Backend가 1분간 캐시한다.

## 2. 제작 요청

```http
POST /api/shortforms/{projectId}/renders
Idempotency-Key: <UUID, 재시도할 때도 같은 값>
Content-Type: application/json

{"style":"VLOG","mood":"CALM","assetIds":["…"],"templateId":"reel_mood_trip_temp_v1","musicId":null}
```

- `assetIds` 순서가 **컷 순서**다. 사진·영상 하나가 컷 하나가 된다.
- `templateId`를 빼면 AI가 사진 구성에 맞춰 고른다("AI 추천").
- `musicId`를 빼면 템플릿의 기본 음악을 쓴다.
- 목록에 없는 값이면 400, 하루 5회를 넘으면 429(`RENDER_LIMIT_EXCEEDED`)다.

## 3. 상태 조회

```http
GET /api/shortforms/{projectId}/renders/{renderId}
```

```json
{"id":"…","status":"PROCESSING","phase":"AWAITING_REVIEW","reviewBusy":false,
 "progress":{"stage":"script","message":"대본 작성 중","current":3,"total":12},
 "storyboardVersion":1,"reviewError":null,"videoUrl":null,"thumbnailUrl":null,"templateId":"…"}
```

| `status` | `phase` | 앱 처리 |
|---|---|---|
| `QUEUED`·`SUBMITTING`·`PROCESSING` | `DRAFTING` | 초안 대기. `progress.message` 표시 |
| `PROCESSING` | `AWAITING_REVIEW` | **검토 화면으로 이동.** 조회를 멈춰도 된다(기한 없음) |
| `PROCESSING` | `RENDERING` | 생성 대기. 사진 컷은 AI 영상이라 컷마다 1~2분 걸린다 |
| `SUCCEEDED` | `DONE` | `videoUrl` 재생 |
| `FAILED` | | `errorMessage` 표시. 새 제작은 새 `Idempotency-Key`로 |

- `progress.current`/`total`이 있으면 진행 막대에 쓴다.
- `reviewError`가 있으면, 승인 후 생성이 멈춰 검토로 돌아온 것이다. 예를 들어 나레이션이 길어 AI가 줄인 초안을 만든 경우다. 스토리보드를 다시 불러와 보여주고 다시 승인받는다.
- **생성 대기 시간:** ComfyUI로 13컷에 약 19분 걸렸다. 앱의 1800초(30분) 제한은 20컷이면 모자랄 수 있다. 제한을 늘리거나, 시간이 지나도 실패 대신 "마이에서 확인"으로 안내하는 게 좋다. Backend는 승인 후 2시간까지 기다린다.

## 4. 검토·편집

`phase == "AWAITING_REVIEW"`일 때만 수정·재생성·승인할 수 있다. 그 외에는 409 `NOT_IN_REVIEW`다.

### 조회

```http
GET /api/shortforms/{projectId}/renders/{renderId}/storyboard
```

응답(키는 snake_case):

| 키 | 화면 |
|---|---|
| `version` | 저장할 때 `base_version`으로 보낸다 |
| `title` | 영상 제목 |
| `audio_mode` | `music`이면 나레이션 입력칸을 숨기고 음악 선택을 보여준다 |
| `music_id`, `music_name` | 현재 음악 |
| `cuts[]` | 컷 목록(순서대로). 아래 표 |
| `photos[]` | 컷의 사진을 바꿀 때 고를 수 있는 자료 |
| `warnings[]` | 승인 전에 보여줄 경고 |

컷(`cuts[]`)에서 쓰는 값:

| 키 | 편집 | 내용 |
|---|---|---|
| `cut_id`, `position` | 순서 변경 | 순서는 `position`만 바꾼다 |
| `narration` | 예 | 대본. `audio_mode == "music"`이면 비어 있고 쓰지 않는다 |
| `shot.on_screen_text` | 예 | 화면 문구. 줄바꿈(`\n`)은 자막에서도 줄바꿈된다 |
| `target_sec` | 예 | 컷 길이(초) |
| `photo_id` | 예 | 사진 교체(`photos[]`의 값) |
| `shot.shot_size`, `shot.camera_move`, `shot.crop_choice` | 예 | 샷 크기, 카메라 움직임, 크롭 후보 번호(1부터) |
| `source_clip` | 예(영상 컷) | `{in_sec, out_sec, audio}`. `audio`: `narration`, `source`, `mix` |
| `media.image_url`, `media.video_url` | | 미리보기. 서명 URL이라 `AsyncImage`·`AVPlayer`에 바로 넣는다(1시간) |
| `shot.crop_options` | | 크롭 후보 사각형(0~1 비율). `media.image_url` 위에 그린다 |

### 저장

조회한 값에서 고친 뒤, **표시용 값(`media`, `flags`, `slot`, `fit_score`, `crop_source`, `framing_refs`, `shot.crop_options`)을 빼고** 보낸다. 모르는 키가 있으면 422다.

```http
PUT /api/shortforms/{projectId}/renders/{renderId}/storyboard
Content-Type: application/json

{"base_version":1,"title":"도시와 강물 사이","music_id":"roi_instrumental_demo",
 "cuts":[{"cut_id":"c_06a5","position":1,"photo_id":"p01","role":"hook","narration":"",
          "target_sec":2.35,"motion_prompt":"…","effect":null,"source_clip":null,"locked":[],
          "shot":{"shot_size":"wide","camera_move":"static","subject_action":"",
                  "crop_choice":1,"on_screen_text":"도시와 강물 사이\n서울","transition_in":"hard_cut"}}]}
```

- 응답 `{"version":2,"changed":true,...}`. 다음 저장과 승인은 새 `version`으로 한다.
- `music_id`를 빼면 그대로 두고, `null`이면 음악을 뺀다. 음악 전용 템플릿은 음악이 있어야 승인된다.
- 409 `STALE_BASE_VERSION`: 그 사이 버전이 바뀌었다(재생성 등). 다시 불러온다.

### AI 재생성

```http
POST .../storyboard/regen
{"cut_ids":["c_06a5"],"fields":["narration","on_screen_text"]}
```

202와 제작 상태(`reviewBusy: true`)가 온다. 상태를 조회해 `reviewBusy == false`가 되고 `storyboardVersion`이 올라가면 스토리보드를 다시 불러온다.

### 승인

```http
POST .../approve
{"version":2,"acceptWarnings":false}
```

- 409 `WARNINGS_NOT_ACCEPTED`: `details.warnings`를 보여주고, 확인을 받으면 `acceptWarnings: true`로 다시 보낸다.
- 성공하면 `phase == "RENDERING"`. 다시 상태 조회로 돌아간다.
- 통신이 끊기면 같은 요청을 다시 보내면 된다(중복 생성 없음).

검토 오류는 `{"code","message","errors","timestamp","details"}` 형식이다. `details`에 `issues`, `warnings`, `changes`가 들어간다.

## 5. 결과 재생

`status == "SUCCEEDED"`인 상태 응답의 `videoUrl`을 `AVPlayer(url:)`에 넣는다.

- 인증 헤더가 필요 없다(서명 URL). 탐색(Range)을 지원하는 1080×1920 MP4다.
- **1시간 뒤 만료(401)된다.** 상태를 다시 조회해 새 `videoUrl`을 받는다(`refreshedVideoURL`).
- `thumbnailUrl`은 첫 컷(제목 화면) 근처의 대표 이미지다. 없으면 `null`이다.
- 커뮤니티에 올릴 때는 `videoUrl`로 파일을 받아 기존 게시 API로 올린다(`renderId`로 바로 게시하는 API는 아직 없음).

## 6. 마이 화면: 내가 만든 숏폼

```http
GET /api/shortforms/videos?page=0&size=20
```

```json
{"items":[{"renderId":"…","projectId":"…","title":"서울의 하루","videoUrl":"https://…","thumbnailUrl":"https://…",
           "templateId":"reel_mood_trip_temp_v1","style":"VLOG","mood":"CALM",
           "createdAt":"…","completedAt":"…"}],
 "page":0,"size":20,"totalElements":1,"totalPages":1,"hasNext":false}
```

- 본인의 **완성된 영상만** 최근 완성 순으로 나온다. 커뮤니티 게시 여부와 상관없고, 서버 데이터라 재설치나 다른 기기에서도 같다.
- **iOS 수정 필요:** 지금 `MyVlogSection`은 커뮤니티에 올린 게시물 ID를 `UserDefaults`(`community.mine`)에 저장해 불러온다. 그래서 만들기만 한 숏폼은 안 보이고 재설치하면 사라진다. "내가 만든 V-log"를 이 API로 채우고, 썸네일은 `thumbnailUrl`, 재생은 `videoUrl`을 쓴다.
- URL이 만료되면 목록을 다시 조회한다. 끌어서 새로고침할 때 다시 부르면 충분하다.

## 7. 서명 URL 정리

| URL | 받는 곳 | 만료되면 |
|---|---|---|
| `videoUrl`, `thumbnailUrl` | 상태 조회, `/videos` | 다시 조회 |
| `media.image_url`, `media.video_url` | 스토리보드 조회 | 스토리보드 다시 조회 |

모두 헤더 없이 쓴다. 기본 1시간이며, 서버 설정(`SHORTFORM_MEDIA_URL_TTL_SECONDS`)으로 바꿀 수 있다.

## 8. iOS 체크리스트

- [ ] 스타일 화면: `options.templates`, `musics`로 선택지 구성, 고른 값을 `templateId`, `musicId`로 전송
- [ ] 상태 조회: `phase`로 화면 분기(`AWAITING_REVIEW` → 검토, `RENDERING` → 생성 대기)
- [ ] 검토 화면: 조회 → 편집(대본, 화면 문구, 순서, 사진, 음악) → 저장(`base_version`) → 승인
- [ ] 음악 전용(`audio_mode == "music"`): 나레이션 입력 숨김, 음악 선택 표시
- [ ] 생성 대기 제한 시간 확대(30분은 20컷에 부족할 수 있음)
- [ ] 결과·미리보기 URL 만료 시 다시 조회
- [ ] 마이 "내가 만든 V-log"를 `GET /videos`로 교체
