#!/usr/bin/env bash
# Proxmox VM(Ubuntu/Debian)에 Docker Engine과 compose 플러그인을 설치한다.
# 사용법: sudo bash deploy/vm/install-docker.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "root 권한이 필요합니다: sudo bash $0" >&2
  exit 1
fi

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "Docker가 이미 설치돼 있습니다: $(docker --version), $(docker compose version --short)"
  exit 0
fi

. /etc/os-release
case "${ID}" in
  ubuntu|debian) ;;
  *) echo "Ubuntu/Debian만 지원합니다 (현재: ${ID}). https://docs.docker.com/engine/install/ 참고" >&2; exit 1 ;;
esac

apt-get update
apt-get install -y ca-certificates curl git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL "https://download.docker.com/linux/${ID}/gpg" -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

# sudo 없이 docker를 쓰도록 설치한 사용자를 docker 그룹에 넣는다. 다시 로그인해야 적용된다.
if [ -n "${SUDO_USER:-}" ] && [ "${SUDO_USER}" != "root" ]; then
  usermod -aG docker "${SUDO_USER}"
  echo "${SUDO_USER}를 docker 그룹에 추가했습니다. 로그아웃 후 다시 접속하세요."
fi

docker --version
docker compose version
