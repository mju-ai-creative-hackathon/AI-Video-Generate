#!/usr/bin/env bash
# LLM PC가 이 VM으로 역방향 SSH 터널(-R 172.17.0.1:18000:127.0.0.1:8000)을 열 수 있게 한다.
# - sshd가 클라이언트가 지정한 주소(172.17.0.1)에 원격 포트를 열도록 허용한다.
# - 공개키를 주면 터널 전용으로 제한해 authorized_keys에 넣는다(셸 접속·다른 포워딩 불가).
# 사용법: sudo bash deploy/vm/allow-ai-tunnel.sh <터널을 열 사용자> [공개키 파일]
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "root 권한이 필요합니다: sudo bash $0 <사용자> [공개키 파일]" >&2
  exit 1
fi
TUNNEL_USER="${1:?터널을 열 사용자 이름이 필요합니다}"
PUBKEY_FILE="${2:-}"
LISTEN="172.17.0.1:18000"

# 1) sshd 설정. 터널 사용자에게만 적용한다.
conf=/etc/ssh/sshd_config.d/ai-tunnel.conf
mkdir -p /etc/ssh/sshd_config.d
if ! grep -qE '^\s*Include\s+/etc/ssh/sshd_config.d/' /etc/ssh/sshd_config; then
  echo "경고: sshd_config에 'Include /etc/ssh/sshd_config.d/*.conf'가 없습니다. 아래 내용을 sshd_config 끝에 직접 넣으세요." >&2
fi
cat > "$conf" <<EOF
# LLM PC → Backend 컨테이너용 역방향 터널 (deploy/vm/allow-ai-tunnel.sh)
Match User ${TUNNEL_USER}
    AllowTcpForwarding remote
    GatewayPorts clientspecified
    PermitListen ${LISTEN}
EOF
sshd -t
systemctl reload ssh 2>/dev/null || systemctl reload sshd
echo "sshd 설정: $conf"

# 2) 터널 전용 공개키 등록
if [ -n "$PUBKEY_FILE" ]; then
  home=$(getent passwd "$TUNNEL_USER" | cut -d: -f6)
  install -d -m 700 -o "$TUNNEL_USER" -g "$TUNNEL_USER" "$home/.ssh"
  keys="$home/.ssh/authorized_keys"
  touch "$keys"; chown "$TUNNEL_USER:$TUNNEL_USER" "$keys"; chmod 600 "$keys"
  key=$(tr -d '\r\n' < "$PUBKEY_FILE")
  line="restrict,port-forwarding,permitlisten=\"${LISTEN}\",command=\"/bin/false\" ${key}"
  if grep -qF "$key" "$keys"; then
    echo "이미 등록된 키입니다."
  else
    echo "$line" >> "$keys"
    echo "터널 전용 키를 $keys에 추가했습니다."
  fi
fi

# 3) 방화벽(ufw)이 켜져 있으면 컨테이너 → 터널 포트만 허용
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow from 172.16.0.0/12 to 172.17.0.1 port 18000 proto tcp comment 'backend -> ai tunnel'
fi

echo "완료. LLM PC에서 터널을 연 뒤 확인: curl -H 'Authorization: Bearer <키>' http://172.17.0.1:18000/api/v1/health"
