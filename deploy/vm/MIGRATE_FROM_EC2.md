# EC2 → VM 데이터 이전

EC2(`3-39-47-78.sslip.io`, Backend `deploy/docker-compose.prod.yml`)의 DB와 업로드 파일을 이 VM(`api.parkserver.xyz`)으로 옮긴다.
덤프 파일과 미디어 묶음에는 사용자 정보가 들어 있으므로 **git에 올리지 않고** 전송 후 지운다.

## 순서

1. 이 브랜치(Backend main 머지본)를 VM에 먼저 배포한다. 댓글 테이블과 숏폼 템플릿 컬럼이 모두 맞게 생긴다.
2. EC2에서 덤프한다. 옮기는 동안 새 데이터가 쌓이지 않게 앱을 멈춘다.

```bash
cd /opt/app
docker compose -f docker-compose.prod.yml stop app
docker compose -f docker-compose.prod.yml exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > seollog.dump
docker volume ls | grep shortform_media
docker run --rm -v app_shortform_media:/data -v "$PWD":/out alpine tar czf /out/media.tgz -C /data .
```

3. `seollog.dump`, `media.tgz`를 VM으로 `scp`한다.
4. VM에서 복원한다. `--clean`이라 VM DB의 기존 데이터는 EC2 내용으로 바뀐다.

```bash
docker compose -f deploy/vm/docker-compose.yml stop app
docker compose -f deploy/vm/docker-compose.yml exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < seollog.dump
docker run --rm -v hackathon-backend_shortform_media:/data -v "$PWD":/in alpine sh -c 'tar xzf /in/media.tgz -C /data'
bash deploy/vm/setup.sh
```

5. `https://api.parkserver.xyz/api/community/posts/explore`에 기존 게시물이 보이면 끝. 두 서버의 `seollog.dump`, `media.tgz`를 지운다.

## 참고

- `deploy/vm/.env`의 `JWT_SECRET`을 EC2와 같게 두면 기존 로그인이 유지된다. 다르면 모두 다시 로그인한다.
- 스키마는 `spring.jpa.hibernate.ddl-auto=update`로 앱 시작 때 맞춰진다.
- 업로드 파일(숏폼 소스, 커뮤니티 영상·썸네일)은 DB가 아니라 `shortform_media` 볼륨(`/app/data`)에 있다.
