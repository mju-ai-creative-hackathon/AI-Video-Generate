# Hackathon Backend

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

## 배포 (AWS)

`main`에 push하면 GitHub Actions가 빌드·테스트 → ECR 이미지 push → SSM으로 EC2에 배포한다.

- 주소: `https://3-39-47-78.sslip.io` (EC2 Elastic IP `3.39.47.78`, Caddy가 HTTPS 인증서 자동 발급)
- 리전 `ap-northeast-2`, EC2 `i-0804f2ce498c85ed3`(t3.small, Amazon Linux 2023), ECR `mju-hackathon-backend`
- EC2 안에서는 `/opt/app`에서 `docker compose -f docker-compose.prod.yml`로 app + PostgreSQL + Caddy가 돈다.
- 운영 비밀값은 EC2의 `/opt/app/.env`에 있다(Git에 없음). 수정 후 Actions에서 `CI/CD` 워크플로를 다시 실행하면 반영된다.
- 접속: `aws ssm start-session --target i-0804f2ce498c85ed3` (SSH 포트는 닫혀 있음)
- Google 로그인 운영 리디렉션 URI: `https://3-39-47-78.sslip.io/login/oauth2/code/google`
- GitHub Secrets: `AWS_ROLE_ARN`(OIDC 배포 역할), `EC2_INSTANCE_ID`
