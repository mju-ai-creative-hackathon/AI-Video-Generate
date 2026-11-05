# Hackathon Backend

Java 21, Spring Boot, Gradle, PostgreSQL로 구성된 백엔드 프로젝트입니다.

## 1. PostgreSQL 실행

Docker Desktop을 실행한 뒤 프로젝트 루트의 VS Code 터미널에서 실행합니다.

```powershell
docker compose up -d
docker compose ps
```

`hackathon-postgres`의 상태가 `healthy`이면 준비가 끝난 것입니다.

기본 접속 정보:

- 주소: `localhost:5432`
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

테스트에서는 Docker와 무관하게 H2 메모리 DB를 사용합니다.

```powershell
.\gradlew.bat test
```

## 4. 종료

```powershell
docker compose down
```

데이터까지 삭제할 때만 `docker compose down -v`를 사용합니다.
