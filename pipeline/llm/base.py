"""LLM 어댑터 인터페이스와 스키마 검증·재시도."""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from ..errors import LLMOutputError

log = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)


class LLMClient(ABC):
    @abstractmethod
    def chat_json(
        self,
        *,
        model: str,
        system: str,
        prompt: str,
        schema: dict[str, Any],
        images: list[Path] | None = None,
    ) -> str:
        """스키마에 맞는 JSON 문자열을 돌려준다."""


def structured_call(
    llm: LLMClient,
    schema_cls: type[T],
    *,
    model: str,
    system: str,
    prompt: str,
    images: list[Path] | None = None,
    max_retries: int = 2,
    check: Callable[[T], None] | None = None,
    failure_dir: Path | None = None,
    label: str = "llm",
) -> T:
    """응답을 검증하고, 실패하면 오류 내용을 넣어 다시 요청한다.

    재시도까지 실패하면 원본 응답을 저장하고 중단한다.
    """
    schema = schema_cls.model_json_schema()
    current = prompt
    raws: list[str] = []
    last_error = ""
    for attempt in range(max_retries + 1):
        raw = llm.chat_json(model=model, system=system, prompt=current, schema=schema, images=images)
        raws.append(raw)
        try:
            result = schema_cls.model_validate_json(raw)
            if check is not None:
                check(result)
            return result
        except ValueError as exc:  # pydantic ValidationError도 ValueError다
            last_error = _describe(exc)
            log.warning("%s 응답 검증 실패 (%d회차): %s", label, attempt + 1, last_error)
            current = (
                f"{prompt}\n\n[이전 응답의 오류]\n{last_error}\n"
                f"[이전 응답]\n{raw[:1500]}\n\n오류를 고쳐 스키마에 맞는 JSON만 다시 출력하세요."
            )
    saved = _save_failure(failure_dir, label, raws)
    hint = f" 원본 응답: {saved}" if saved else ""
    raise LLMOutputError(
        f"{label}: {len(raws)}회 시도 후에도 스키마를 통과하지 못했습니다. {last_error}{hint}"
    )


def _describe(exc: ValueError) -> str:
    if isinstance(exc, ValidationError):
        parts = [
            f"{'.'.join(str(p) for p in err['loc']) or '(전체)'}: {err['msg']}"
            for err in exc.errors()[:8]
        ]
        return "; ".join(parts)
    return str(exc)


def _save_failure(failure_dir: Path | None, label: str, raws: list[str]) -> Path | None:
    if failure_dir is None:
        return None
    failure_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    path = failure_dir / f"{label}_{stamp}.txt"
    path.write_text("\n\n----- 다음 시도 -----\n\n".join(raws), encoding="utf-8")
    return path
