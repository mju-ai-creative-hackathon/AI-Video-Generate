# iOS Google 로그인 설정

## 인증 흐름

1. iOS 앱이 Google Sign-In SDK로 사용자를 인증합니다.
2. iOS 앱이 Google에서 받은 ID Token을 백엔드에 HTTPS로 전송합니다.
3. 백엔드는 Google 공개키로 토큰 서명을 검증합니다.
4. 백엔드는 토큰의 발급자(`iss`), 만료(`exp`), 대상(`aud`), 이메일 인증 여부를 검증합니다.
5. 검증된 Google 계정을 신규 생성하거나 같은 이메일의 기존 계정과 연결합니다.
6. 백엔드가 서비스 자체 JWT를 iOS 앱에 JSON으로 반환합니다.

웹 프론트엔드 URL로 리다이렉트하는 과정은 없습니다.

## 백엔드 API

```http
POST /api/auth/google
Content-Type: application/json

{
  "idToken": "GOOGLE_ID_TOKEN_FROM_IOS"
}
```

성공 응답은 일반 로그인과 동일합니다.

```json
{
  "accessToken": "SERVICE_JWT",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "refreshToken": "RANDOM_REFRESH_TOKEN",
  "refreshExpiresIn": 604800,
  "user": {
    "id": "...",
    "email": "user@example.com",
    "name": "사용자",
    "profileImageUrl": "https://...",
    "createdAt": "..."
  }
}
```

## Google Cloud Console

같은 Google Cloud 프로젝트에 클라이언트 두 개가 필요합니다.

1. `iOS` 유형: Xcode 프로젝트의 실제 Bundle Identifier를 입력합니다.
2. `웹 애플리케이션` 유형: 백엔드 인증용 Server Client ID로 사용합니다.

웹 애플리케이션 유형에서 발급된 Client ID를 백엔드 환경 변수에 설정합니다. 이 흐름에서는 백엔드가 Google Client Secret을 사용하지 않습니다.

```dotenv
GOOGLE_SERVER_CLIENT_ID=191486826471-hj88egpu3at3trb0uo5m08q7md915t5a.apps.googleusercontent.com
```

iOS 앱에는 iOS Client ID와 Server Client ID를 모두 설정해야 합니다. iOS 앱이 요청한 ID Token의 `aud` 값은 백엔드의 `GOOGLE_SERVER_CLIENT_ID`와 일치해야 합니다.

현재 발급된 Server Client ID는 로컬 기본값으로도 설정되어 있습니다. 운영 환경에서 별도 Google Cloud 프로젝트를 사용하게 되면 `GOOGLE_SERVER_CLIENT_ID` 환경 변수로 기본값을 덮어쓸 수 있습니다. Client Secret은 이 인증 흐름에서 사용하지 않습니다.
