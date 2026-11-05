"""LLM 재시도·중단, Ollama 요청 형식, 사진 분석."""

from __future__ import annotations

import base64
import json

import httpx
import pytest
from conftest import FakeLLM
from pydantic import BaseModel

from pipeline.analyze import analyze_photo, apply_detection
from pipeline.config import LLMSettings
from pipeline.errors import LLMError, LLMOutputError
from pipeline.llm.base import structured_call
from pipeline.llm.ollama import OllamaLLM
from pipeline.schemas import LLMPhotoAnalysis, normalize_box


class Simple(BaseModel):
    value: int


def call(llm, **kwargs):  # noqa: ANN001, ANN201
    return structured_call(llm, Simple, model="m", system="s", prompt="원래 요청", **kwargs)


class TestStructuredCall:
    def test_retries_with_error_message(self):
        llm = FakeLLM()
        llm.queue = ['{"value": "숫자 아님"}', '{"value": 3}']
        assert call(llm).value == 3
        assert len(llm.calls) == 2
        retry_prompt = llm.calls[1]["prompt"]
        assert "원래 요청" in retry_prompt
        assert "이전 응답의 오류" in retry_prompt
        assert "value" in retry_prompt

    def test_broken_json_is_retried(self):
        llm = FakeLLM()
        llm.queue = ["JSON 아님", '{"value": 1}']
        assert call(llm).value == 1

    def test_stops_after_three_attempts_and_saves_raw(self, tmp_path):
        llm = FakeLLM()
        llm.queue = ["나쁜 응답1", "나쁜 응답2", "나쁜 응답3", '{"value": 1}']
        with pytest.raises(LLMOutputError):
            call(llm, failure_dir=tmp_path, label="test")
        assert len(llm.calls) == 3
        saved = list(tmp_path.glob("test_*.txt"))
        assert len(saved) == 1
        assert "나쁜 응답3" in saved[0].read_text(encoding="utf-8")

    def test_custom_check_triggers_retry(self):
        llm = FakeLLM()
        llm.queue = ['{"value": 1}', '{"value": 2}']

        def check(result):  # noqa: ANN001, ANN202
            if result.value != 2:
                raise ValueError("2여야 합니다")

        assert call(llm, check=check).value == 2
        assert "2여야 합니다" in llm.calls[1]["prompt"]


class TestOllama:
    def _client(self, handler):  # noqa: ANN001, ANN202
        return OllamaLLM(
            "http://127.0.0.1:11434", LLMSettings(), transport=httpx.MockTransport(handler)
        )

    def test_request_shape(self, tmp_path):
        captured = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["url"] = str(request.url)
            captured["body"] = json.loads(request.content)
            return httpx.Response(200, json={"message": {"content": '{"value": 1}'}})

        image = tmp_path / "a.jpg"
        image.write_bytes(b"\xff\xd8image")
        text = self._client(handler).chat_json(
            model="qwen3-vl:8b",
            system="sys",
            prompt="hello",
            schema={"type": "object"},
            images=[image],
        )
        body = captured["body"]
        assert text == '{"value": 1}'
        assert captured["url"].endswith("/api/chat")
        assert body["format"] == {"type": "object"}
        assert body["stream"] is False
        assert body["keep_alive"] == 0  # 호출 후 바로 내린다
        assert "think" not in body
        user = body["messages"][1]
        assert base64.b64decode(user["images"][0]) == b"\xff\xd8image"

    def test_missing_model_gives_pull_hint(self):
        client = self._client(lambda _r: httpx.Response(404, json={"error": "not found"}))
        with pytest.raises(LLMError, match="ollama pull m"):
            client.chat_json(model="m", system="", prompt="", schema={})

    def test_connection_error_is_friendly(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("refused", request=request)

        with pytest.raises(LLMError, match="연결하지 못했습니다"):
            self._client(handler).chat_json(model="m", system="", prompt="", schema={})


class TestAnalysis:
    def test_invalid_box_is_dropped_not_fatal(self):
        assert normalize_box([0, 0, 500, 500]) is None
        assert normalize_box([0.5, 0.5, 0.2, 0.9]) is None
        assert normalize_box("x") is None
        assert normalize_box([0.1, 0.1, 0.9, 0.9]) == [0.1, 0.1, 0.9, 0.9]

    def _result(self, subject_type="인물", box=(0.1, 0.1, 0.9, 0.9)):  # noqa: ANN001, ANN202
        return LLMPhotoAnalysis(
            caption="c",
            mood="m",
            subject_type=subject_type,
            subject_box=list(box) if box else None,
            face_visible=False,
        )

    def test_detector_wins_for_people(self):
        faces = [[0.4, 0.2, 0.5, 0.3]]
        result = apply_detection(self._result(), faces)
        assert result.subject_box == faces[0]
        assert result.subject_box_source == "detector"
        assert result.faces == faces

    def test_llm_box_kept_for_food_even_with_false_face(self):
        result = apply_detection(self._result("음식"), [[0.4, 0.2, 0.5, 0.3]])
        assert result.subject_box == [0.1, 0.1, 0.9, 0.9]
        assert result.subject_box_source == "llm"

    def test_no_box_at_all(self):
        result = apply_detection(self._result("풍경", None), [])
        assert result.subject_box is None
        assert result.subject_box_source == "none"

    def test_analyze_sends_image_to_vision_model(self, tmp_path, llm):
        image = tmp_path / "p.jpg"
        image.write_bytes(b"x")
        result = analyze_photo(llm, LLMSettings(vision_model="vision"), image)
        assert llm.calls[0]["model"] == "vision"
        assert llm.calls[0]["images"] == [image]
        assert result.subject_type == "음식"
