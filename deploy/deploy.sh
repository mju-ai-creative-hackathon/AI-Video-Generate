#!/bin/bash
# EC2에서 실행: 최신 이미지 pull 후 재기동. 사용법: deploy.sh <image-uri>
set -euo pipefail
cd /opt/app
IMAGE="$1"
REGISTRY="${IMAGE%%/*}"
DOMAIN=$(grep ^DOMAIN= .env | cut -d= -f2)
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin "$REGISTRY"
sed -i '/^APP_IMAGE=/d' .env && echo "APP_IMAGE=$IMAGE" >> .env
docker compose -f docker-compose.prod.yml pull app
docker compose -f docker-compose.prod.yml up -d --remove-orphans
for i in $(seq 1 40); do
  code=$(curl -sk -o /dev/null -w '%{http_code}' --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/api/auth/me" || true)
  # 401 = 앱이 떠서 인증 실패 응답을 준 것
  if [ "$code" != "000" ] && [ "$code" != "502" ]; then echo "healthy ($code)"; docker image prune -af >/dev/null; exit 0; fi
  sleep 3
done
docker compose -f docker-compose.prod.yml logs --tail 80 app
exit 1
