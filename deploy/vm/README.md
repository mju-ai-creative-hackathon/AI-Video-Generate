# Proxmox VM 배포

```
iOS 앱 → 도메인 → 공유기 → Nginx Proxy Manager(HTTPS) → pfSense → 이 VM:8080 (Backend + PostgreSQL)
                                                                   └ 172.17.0.1:18000 ← SSH 역방향 터널 ← LLM PC(vlog serve)
```

- HTTPS는 NPM이 처리하고, 이 VM은 HTTP 8080만 연다. Caddy는 쓰지 않는다.
- LLM PC는 인터넷에도, 이 VM의 LAN에도 포트를 열지 않는다. LLM PC가 이 VM으로 SSH 접속해 터널을 연다.
- CORS는 설정하지 않는다. 클라이언트가 iOS 앱뿐이고, CORS는 브라우저에만 적용된다. 웹 프론트가 생기면 그때 허용 주소를 추가한다.

## 1. Docker 설치 (VM, 한 번)

```bash
git clone -b feat/backend https://github.com/mju-ai-creative-hackathon/AI-Video-Generate.git backend
```

```bash
cd backend && sudo bash deploy/vm/install-docker.sh
```

docker 그룹 적용을 위해 로그아웃 후 다시 접속한다.

## 2. Backend 실행 (VM)

```bash
bash deploy/vm/setup.sh
```

처음 실행하면 `deploy/vm/.env`를 만들고 `POSTGRES_PASSWORD`, `JWT_SECRET`을 무작위로 채운다. `GEMINI_API_KEY`, `KAKAO_REST_API_KEY`, `GOOGLE_SERVER_CLIENT_ID`는 직접 넣고 다시 실행한다. 첫 빌드는 Gradle을 받느라 몇 분 걸린다. 코드를 바꾼 뒤에도 `git pull` 후 같은 명령을 실행한다.

`APP_BIND`에 이 VM의 IP를 적으면 그 인터페이스에만 8080을 연다.

## 3. NPM과 pfSense

- **pfSense:** NPM → 이 VM TCP 8080만 허용한다.
- **NPM Proxy Host:** 도메인 → `http://<VM IP>:8080`. SSL 탭에서 인증서 발급, Force SSL.
- **NPM Advanced 탭:** 영상 업로드(파일당 최대 100MiB)와 긴 요청을 위해 넣는다.

```nginx
client_max_body_size 600m;
proxy_request_buffering off;
proxy_read_timeout 300s;
proxy_send_timeout 300s;
```

NPM이 `X-Forwarded-Proto`, `Host`를 넘기고 Backend는 `SERVER_FORWARD_HEADERS_STRATEGY=framework`로 이를 믿는다. 그래서 결과 영상 URL이 `https://<도메인>/...`로 만들어진다.

## 4. AI 서버 터널

**VM에서** 터널 사용자를 허용한다. LLM PC가 만든 터널 전용 공개키를 넣으면 그 키는 `172.17.0.1:18000` 포워딩만 할 수 있다(셸 접속 불가).

```bash
sudo bash deploy/vm/allow-ai-tunnel.sh <사용자> /tmp/vlog_tunnel.pub
```

**LLM PC에서** 터널을 연다. 준비와 자동 실행은 AI-Video-Generate 레포 README의 "Backend와 연결"을 본다.

```powershell
pwsh -File scripts\ai-tunnel.ps1 -Server <VM 주소> -User <사용자>
```

**VM에서** 연결을 확인한다.

```bash
curl -H "Authorization: Bearer <LLM PC의 API_KEY>" http://172.17.0.1:18000/api/v1/health
```

컨테이너 안에서도 터널 포트에 닿는지 본다(`ok`가 나오면 된다).

```bash
docker compose -f deploy/vm/docker-compose.yml exec app bash -c 'exec 3<>/dev/tcp/host.docker.internal/18000 && echo ok'
```

## 5. AI 연동 켜기

`deploy/vm/.env`에 `SHORTFORM_AI_API_KEY`(LLM PC의 `API_KEY`)를 넣고, **검토 화면이 있는 iOS 앱이 나온 뒤** `SHORTFORM_AI_ENABLED=true`로 바꿔 `setup.sh`를 다시 실행한다. 켜기 전의 앱은 검토 단계에서 30분 기다리다 실패한다.

`GET https://<도메인>/api/shortforms/options`의 `renderingAvailable`이 `true`면 연결된 것이다.

## 운영

```bash
docker compose -f deploy/vm/docker-compose.yml logs -f app
```

```bash
docker compose -f deploy/vm/docker-compose.yml down
```

데이터는 Docker 볼륨 `postgres_data`, `shortform_media`에 남는다. `down -v`는 데이터까지 지우므로 쓰지 않는다.
