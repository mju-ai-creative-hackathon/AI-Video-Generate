"""API 키 인증과 서명된 미디어 URL.

<img>와 <video>는 인증 헤더를 보낼 수 없으므로 만료 시간이 있는 서명을 URL에 넣는다.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from urllib.parse import urlencode

from fastapi import HTTPException, Request, Security
from fastapi.security import APIKeyHeader, HTTPAuthorizationCredentials, HTTPBearer

LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}

api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)
# Backend(Spring)는 `Authorization: Bearer <키>`로 보낸다.
bearer_header = HTTPBearer(auto_error=False)
# 키가 없는 루프백 실행에서 쓰는 서명 비밀값. 서버를 다시 켜면 이전 URL은 무효가 된다.
_PROCESS_SECRET = secrets.token_bytes(32)


def is_loopback(host: str) -> bool:
    return host in LOOPBACK_HOSTS


def has_valid_key(request: Request) -> bool:
    """요청 헤더에 맞는 API 키가 있는지. 키를 설정하지 않았으면 항상 참이다."""
    expected = request.app.state.ctx.settings.env.api_key
    if not expected:
        return True  # 키가 없으면 루프백으로만 시작할 수 있다 (`vlog serve`에서 확인)
    key = request.headers.get("X-API-Key")
    if not key:
        scheme, _, token = request.headers.get("Authorization", "").partition(" ")
        key = token.strip() if scheme.lower() == "bearer" else None
    return bool(key) and secrets.compare_digest(key.encode("utf-8"), expected.encode("utf-8"))


def require_api_key(
    request: Request,
    _key: str | None = Security(api_key_header),
    _bearer: HTTPAuthorizationCredentials | None = Security(bearer_header),
) -> None:
    # Security 인자는 /docs에 두 방식을 표시하려고 둔다. 확인은 헤더를 직접 읽는다.
    if not has_valid_key(request):
        raise HTTPException(status_code=401, detail="API 키가 없거나 올바르지 않습니다.")


def _secret(api_key: str) -> bytes:
    return api_key.encode("utf-8") if api_key else _PROCESS_SECRET


def _signature(path: str, expires: int, api_key: str) -> str:
    message = f"{path}\n{expires}".encode()
    return hmac.new(_secret(api_key), message, hashlib.sha256).hexdigest()


def sign_url(path: str, api_key: str, ttl_sec: int) -> str:
    expires = int(time.time()) + ttl_sec
    query = urlencode({"expires": expires, "sig": _signature(path, expires, api_key)})
    return f"{path}?{query}"


def verify_signed_url(path: str, expires: int, signature: str, api_key: str) -> bool:
    if expires < time.time():
        return False
    return hmac.compare_digest(_signature(path, expires, api_key), signature)
