#!/usr/bin/env bash
# VM에서 Backend를 빌드하고 띄운다. 처음 실행하면 .env를 만들고 비어 있는 비밀값을 무작위로 채운다.
# 사용법: bash deploy/vm/setup.sh        (다시 실행하면 최신 코드로 다시 빌드)
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  echo ".env를 만들었습니다."
fi

fill_secret() {
  local name="$1" bytes="$2"
  if grep -qE "^${name}=$" .env; then
    local value
    value=$(head -c "$bytes" /dev/urandom | base64 | tr -d '\n/+=' | head -c $((bytes * 4 / 3)))
    sed -i "s|^${name}=$|${name}=${value}|" .env
    echo "${name}를 무작위 값으로 채웠습니다."
  fi
}
fill_secret POSTGRES_PASSWORD 24
fill_secret JWT_SECRET 48

if grep -qE '^SHORTFORM_AI_ENABLED=true' .env && grep -qE '^SHORTFORM_AI_API_KEY=$' .env; then
  echo "SHORTFORM_AI_ENABLED=true인데 SHORTFORM_AI_API_KEY가 비어 있습니다. LLM PC의 API_KEY를 넣으세요." >&2
  exit 1
fi

docker compose up -d --build

port=$(grep -E '^APP_PORT=' .env | cut -d= -f2)
port=${port:-8080}
echo "Backend가 뜨기를 기다립니다(첫 빌드는 몇 분 걸립니다)..."
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${port}/api/shortforms/options" >/dev/null 2>&1; then
    echo "실행 중: http://127.0.0.1:${port}"
    curl -fsS "http://127.0.0.1:${port}/api/shortforms/options"; echo
    exit 0
  fi
  sleep 3
done
echo "응답이 없습니다. 로그:" >&2
docker compose logs --tail 80 app >&2
exit 1
