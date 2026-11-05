# Google 로그인 설정

## 로그인 흐름

1. 프론트엔드가 브라우저를 `GET http://localhost:8080/api/auth/google`로 이동시킵니다.
2. 백엔드는 Google 인증 화면으로 리다이렉트합니다.
3. Google은 인증 후 백엔드의 `/login/oauth2/code/google`로 돌아옵니다.
4. 백엔드는 검증된 Google 이메일로 사용자를 생성하거나 기존 계정에 연결하고 자체 JWT를 발급합니다.
5. 성공 시 프론트엔드의 `/oauth/callback`으로, 실패 시 `/login?error=...`로 리다이렉트합니다.

성공 리다이렉트의 기본 형식은 다음과 같습니다.

```text
http://localhost:3000/oauth/callback?accessToken=...&tokenType=Bearer&expiresIn=3600
```

프론트엔드는 `accessToken`을 읽은 직후 브라우저 history API를 사용해 URL에서 토큰 파라미터를 제거해야 합니다.

## Google Cloud Console

Google Cloud Console에서 웹 애플리케이션 OAuth 2.0 클라이언트를 만들고 다음 값을 승인된 리디렉션 URI에 등록합니다.

```text
http://localhost:8080/login/oauth2/code/google
```

운영 환경에서는 실제 백엔드 HTTPS 주소를 별도로 등록해야 합니다. 등록한 값은 `GOOGLE_REDIRECT_URI`와 정확히 일치해야 합니다.

## 환경 변수

```dotenv
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google
FRONTEND_OAUTH_SUCCESS_URL=http://localhost:3000/oauth/callback
FRONTEND_OAUTH_FAILURE_URL=http://localhost:3000/login
```

`.env` 파일은 Spring Boot가 자동으로 읽지 않으므로 IDE 실행 구성이나 셸 환경 변수로 전달해야 합니다.
