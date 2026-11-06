# 커뮤니티 탐색 API

게시물별 댓글 API는 [COMMUNITY_COMMENTS_API.md](COMMUNITY_COMMENTS_API.md)를 참고하세요.

숏폼 영상 게시물을 등록하고, 검색창과 해시태그 칩으로 검색하거나 랜덤 탐색 목록을 조회합니다.
이 저장소는 백엔드이므로 이미지의 그리드 화면은 프론트에서 응답의 `thumbnailUrl`로 구성합니다.

기본 경로: `/api/community/posts`

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| POST | `/` | 로그인 사용자 게시물 등록 |
| GET | `/explore` | 검색·태그 필터를 적용한 랜덤 탐색 |
| GET | `/tags` | 공개 게시물에서 집계한 인기 해시태그 |
| GET | `/{id}` | 게시물 상세 |
| POST | `/{id}/import` | 게시물 코스를 내 일정으로 가져와 바로 시작 |
| GET | `/{id}/media/{mediaId}` | 공개된 게시물의 원본 미디어 |
| PATCH | `/{id}` | 작성자 본인 게시물 수정(제목·본문·태그·연결 코스) |
| DELETE | `/{id}` | 작성자 본인 게시물 삭제 |

## 게시물 등록

등록·삭제에는 기존 로그인에서 받은 `Authorization: Bearer <accessToken>`이 필요합니다.
`multipart/form-data`의 `post` 파트에 JSON 메타데이터, `video` 파트에 숏폼 영상,
`cover` 파트에 피드 썸네일용 이미지를 전달합니다.

```json
{"title":"한강에서 보낸 하루","caption":"함께 걷고 촬영한 서울","tags":["#커플여행","서울"],"courseId":1}
```

```bash
curl -X POST http://localhost:8080/api/community/posts \
  -H 'Authorization: Bearer ACCESS_TOKEN' \
  -F 'post={"title":"한강에서 보낸 하루","caption":"서울 산책","tags":["서울"],"courseId":1};type=application/json' \
  -F 'video=@shortform.mp4' \
  -F 'cover=@cover.png'
```

제목 최대 100자, 본문 최대 3000자, 태그 최대 10개이며 태그당 최대 30자입니다.
`courseId`는 선택 사항입니다. 프리셋 코스 또는 본인 소유 코스만 연결할 수 있으며, 연결하면
게시물 응답의 `course`에 코스 ID와 게시 당시 제목이 포함됩니다.
태그는 앞의 `#` 제거, 소문자·Unicode 정규화 후 저장합니다. 문자·숫자·밑줄만 허용합니다.
게시물당 영상은 1개이며 MP4/MOV/WebM을 지원합니다. 일반 이미지 게시물과 복수 미디어는 지원하지 않습니다.
커버는 JPEG/PNG/WebP만 지원하며 최대 10MiB, 영상은 최대 100MiB입니다.
선언 MIME과 파일 시그니처를 검사하며 영상 코덱·길이·화질을 검증하거나 변환하지는 않습니다.

`cover`는 필수이며 그리드용 `thumbnailUrl`이 가리킵니다. 커버는 본문의 `media` 배열에는 포함되지 않습니다.

201 응답에는 `id`, `title`, `caption`, `author`, `tags`, `thumbnailUrl`, `media`, `createdAt`이 포함됩니다.
`author`는 공개 프로필인 `id`, `name`, `profileImageUrl`만 제공하며 이메일은 노출하지 않습니다.
각 `media`는 `id`, `kind`, `contentType`, `sizeBytes`, `url`을 포함합니다.
미디어 URL은 이 백엔드의 상대 경로이므로 프론트의 API 기본 주소를 붙여 사용합니다.

## 관련 코스 보기

게시물 응답의 `course`가 `{"id": 5, "title": "..."}`이면 연결된 코스가 있는 것입니다.
릴스 상세의 "관련 코스 확인하기" 버튼은 `GET /api/courses/{course.id}`로 코스 상세를 띄웁니다.
`course`가 `null`이면 버튼을 숨깁니다.

## 게시물 수정

```http
PATCH /api/community/posts/{postId}
Authorization: Bearer ACCESS_TOKEN
Content-Type: application/json

{"title":"바뀐 제목","tags":["노을","서대문"],"courseId":5}
```

보낸 항목만 바꾸고 빠진 항목(`null`)은 그대로 둡니다. `tags`를 보내면 목록 전체를 교체합니다.
`courseId`는 등록 때와 같이 프리셋 코스 또는 본인 소유 코스만 연결할 수 있습니다.
연결을 떼려면 `{"removeCourse":true}`를 보내고, `courseId`와 함께 보내면 400입니다.
작성자 본인만 수정할 수 있으며 다른 사람의 게시물이면 404, 로그인이 없으면 401입니다.
영상과 커버는 바꿀 수 없습니다.

## 커뮤니티 코스를 내 일정으로 시작

코스가 연결된 다른 사용자의 게시물에서 다음 API를 호출합니다.

```http
POST /api/community/posts/{postId}/import
Authorization: Bearer ACCESS_TOKEN
```

서버는 게시물의 코스를 요청자 소유의 `IMPORTED` 코스로 복제하고, 복제된 코스로 여행 기록을
즉시 시작합니다. 장소의 일차·순서·좌표·이미지, 출발지, 촬영 미션을 모두 복사하므로 이후
원본 코스가 변경되거나 삭제되어도 가져온 일정에는 영향을 주지 않습니다.

201 응답의 `courseId`는 복제된 코스 ID이고 `diary`는 `/api/diaries`의 진행 중 기록과 같은 형식입니다.
`Location` 헤더는 생성된 `/api/diaries/{diaryId}`를 가리킵니다. 진행 중인 일정이 이미 있으면 409,
로그인이 없으면 401, 게시물 또는 연결 코스가 없으면 404를 반환합니다. 실패한 요청에서 생성한
복제 코스는 트랜잭션과 함께 롤백됩니다.

## 검색과 랜덤 탐색

```http
GET /api/community/posts/explore?keyword=한강&tag=서울&size=20
```

제목·본문·해시태그를 대소문자 구분 없이 부분 검색합니다. `%`, `_`는 일반 문자로 처리합니다.
`tag`는 정규화한 해시태그의 정확한 일치이며 검색어와 함께 보내면 AND 조건입니다.
`keyword` 최대 100자, `size`는 1~50입니다. 검색 결과가 없으면 빈 `items`를 반환합니다.

응답 필드:

- `items`: 그리드 카드 및 상세에 사용할 게시물 배열
- `seed`, `snapshot`: 현재 랜덤 탐색 세션의 기준
- `nextCursor`, `hasNext`: 다음 페이지 조회 정보

처음 열 때 seed 없이 호출하면 새 랜덤 순서가 생성됩니다. 무한 스크롤에서는 응답의
`seed`, `snapshot`, `nextCursor`를 다음 요청의 `seed`, `snapshot`, `cursor`로 그대로 전달합니다.
검색어·태그·로그인 사용자도 같은 탐색 세션 동안 유지해야 합니다.
검색어·태그를 바꾸거나 새로고침하면 기존 seed/snapshot/cursor를 버리고 새 목록을 요청합니다.
이 방식으로 페이지마다 재추첨하여 같은 게시물이 반복되는 문제를 방지합니다.
기준 시각 이후 생성된 글은 새 탐색 세션에서 표시됩니다. 삭제되거나 탈퇴한 작성자의 글은 즉시 제외됩니다.

익명 조회는 모든 공개 게시물을 탐색합니다. Bearer 토큰이 있으면 본인 게시물을 제외하여
다른 사람의 게시물만 표시합니다. 잘못된 토큰을 보내면 익명으로 처리하지 않고 401을 반환합니다.
`GET /tags`는 실제 공개 게시물 태그를 게시물 수 내림차순으로 반환하며 동률은 태그 순으로 정렬합니다.

## 공개 범위·삭제

등록한 커뮤니티 게시물과 첨부 미디어는 공개됩니다. 숏폼 프로젝트의 비공개 원본을 자동 게시하지 않습니다.
다른 사용자의 글 삭제는 404, 인증 없는 등록·삭제는 401입니다.
삭제된 글의 상세·미디어도 404이며 작성자가 탈퇴한 글 역시 목록·상세·미디어·태그 집계에서 제외됩니다.
탈퇴 사용자의 잔여 파일 정리 및 보관 기한은 별도 운영 정책으로 확장해야 합니다.
원본은 기존 로컬 미디어 저장소와 Docker 영속 볼륨을 사용합니다. 여러 서버 운영 시 공유 저장소가 필요합니다.
댓글·좋아요·팔로우·게시물 수정·신고·관리자 검수는 이번 범위에 포함하지 않습니다.

## 검증

Docker Desktop을 실행하고 Java 21로 `./gradlew build`를 실행합니다.
커뮤니티 통합 테스트는 개발 DB와 분리된 PostgreSQL Testcontainer를 사용하여 검색,
안정적인 랜덤 페이지, 본인 제외, 공개 미디어, 소유권, 탈퇴 후 숨김, 업로드 검증을 확인합니다.
