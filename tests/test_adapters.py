"""ElevenLabs·ComfyUI 어댑터 (네트워크 없이 가짜 응답으로), Ken Burns 필터."""

from __future__ import annotations

import base64
import json
from pathlib import Path

import httpx
import pytest
from conftest import REPO_ROOT

from pipeline.config import TTSSettings, VideoSettings
from pipeline.errors import ConfigError, TTSError, VideoError
from pipeline.tts.base import TTSRequest
from pipeline.tts.elevenlabs import ElevenLabsTTS
from pipeline.tts.silent import SilentTTS
from pipeline.video.base import ClipRequest
from pipeline.video.comfyui import PLACEHOLDER, ComfyUIVideo, _find_video_output, render_workflow
from pipeline.video.kenburns import zoompan_filter


def tts_response() -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "audio_base64": base64.b64encode(b"mp3bytes").decode(),
            "alignment": {
                "characters": list("안녕 하세요"),
                "character_start_times_seconds": [0.0, 0.1, 0.2, 0.3, 0.4, 0.5],
                "character_end_times_seconds": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6],
            },
        },
    )


def make_tts(handler, **settings):  # noqa: ANN001, ANN003, ANN201
    sleeps: list[float] = []
    client = ElevenLabsTTS(
        "key",
        TTSSettings(voice_id="voice1", **settings),
        transport=httpx.MockTransport(handler),
        sleep=sleeps.append,
    )
    return client, sleeps


class TestElevenLabs:
    def test_request_and_response(self, tmp_path):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen["url"] = request.url
            seen["key"] = request.headers["xi-api-key"]
            seen["body"] = json.loads(request.content)
            return tts_response()

        client, _ = make_tts(handler)
        out = tmp_path / "a" / "x.mp3"
        result = client.synthesize(TTSRequest("안녕 하세요", "앞 문장", "뒤 문장"), out)

        assert seen["url"].path == "/v1/text-to-speech/voice1/with-timestamps"
        assert seen["url"].params["output_format"] == "mp3_44100_128"
        assert seen["key"] == "key"
        assert seen["body"]["previous_text"] == "앞 문장"
        assert seen["body"]["next_text"] == "뒤 문장"
        assert seen["body"]["model_id"] == "eleven_multilingual_v2"
        assert "language_code" not in seen["body"]  # multilingual_v2는 지원하지 않는다
        assert out.read_bytes() == b"mp3bytes"
        assert [w.text for w in result.words] == ["안녕", "하세요"]

    def test_first_and_last_cut_omit_context(self, tmp_path):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen["body"] = json.loads(request.content)
            return tts_response()

        client, _ = make_tts(handler)
        client.synthesize(TTSRequest("문장"), tmp_path / "x.mp3")
        assert "previous_text" not in seen["body"]
        assert "next_text" not in seen["body"]

    @pytest.mark.parametrize("status", [429, 500, 503])
    def test_retries_with_exponential_backoff(self, tmp_path, status):
        responses = [httpx.Response(status), httpx.Response(status), tts_response()]
        client, sleeps = make_tts(lambda _r: responses.pop(0))
        client.synthesize(TTSRequest("문장"), tmp_path / "x.mp3")
        assert sleeps == [1.0, 2.0]

    def test_honours_retry_after(self, tmp_path):
        responses = [httpx.Response(429, headers={"retry-after": "7"}), tts_response()]
        client, sleeps = make_tts(lambda _r: responses.pop(0))
        client.synthesize(TTSRequest("문장"), tmp_path / "x.mp3")
        assert sleeps == [7.0]

    def test_gives_up_after_limit(self, tmp_path):
        calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(request)
            return httpx.Response(500, text="boom")

        client, _ = make_tts(handler, max_retries=2)
        with pytest.raises(TTSError, match="3회"):
            client.synthesize(TTSRequest("문장"), tmp_path / "x.mp3")
        assert len(calls) == 3

    def test_client_error_is_not_retried(self, tmp_path):
        calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(request)
            return httpx.Response(401, text="bad key")

        client, sleeps = make_tts(handler)
        with pytest.raises(TTSError, match="401"):
            client.synthesize(TTSRequest("문장"), tmp_path / "x.mp3")
        assert len(calls) == 1
        assert sleeps == []

    def test_network_error_is_retried(self, tmp_path):
        state = {"n": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            state["n"] += 1
            if state["n"] == 1:
                raise httpx.ConnectError("down", request=request)
            return tts_response()

        client, _ = make_tts(handler)
        client.synthesize(TTSRequest("문장"), tmp_path / "x.mp3")
        assert state["n"] == 2

    def test_missing_key_or_voice_is_config_error(self):
        with pytest.raises(ConfigError, match="ELEVENLABS_API_KEY"):
            ElevenLabsTTS("", TTSSettings(voice_id="v"))
        with pytest.raises(ConfigError, match="voice_id"):
            ElevenLabsTTS("k", TTSSettings(voice_id=""))

    def test_signature_changes_with_voice(self):
        a = ElevenLabsTTS("k", TTSSettings(voice_id="a")).signature()
        b = ElevenLabsTTS("k", TTSSettings(voice_id="b")).signature()
        assert a != b
        assert "k" not in json.dumps(a)  # API 키가 해시 입력에 들어가지 않는다


def test_silent_tts_makes_playable_wav(tmp_path):
    import wave

    out = tmp_path / "s.wav"
    result = SilentTTS(5.5).synthesize(TTSRequest("가나다라 마바사아"), out)
    with wave.open(str(out), "rb") as handle:
        assert handle.getnframes() / handle.getframerate() == pytest.approx(8 / 5.5, abs=0.01)
    assert len(result.words) == 2


class TestWorkflow:
    values = {"PROMPT": "a cup", "SEED": 42, "FRAMES": 33}

    def test_whole_placeholder_keeps_type(self):
        rendered = render_workflow({"seed": "{{SEED}}", "n": ["{{FRAMES}}"]}, self.values)
        assert rendered == {"seed": 42, "n": [33]}

    def test_inline_placeholder_becomes_text(self):
        assert render_workflow("x_{{SEED}}", self.values) == "x_42"

    def test_non_string_values_pass_through(self):
        assert render_workflow({"a": 1.5, "b": True, "c": None}, {}) == {"a": 1.5, "b": True, "c": None}

    def test_missing_value_is_error(self):
        with pytest.raises(VideoError, match="NOPE"):
            render_workflow("{{NOPE}}", self.values)

    def test_shipped_workflow_placeholders_are_all_filled(self, tmp_path):
        path = REPO_ROOT / "workflows" / "wan22_i2v.json"
        client = ComfyUIVideo("http://x", VideoSettings(), path, ws_factory=None)
        request = ClipRequest(tmp_path / "a.jpg", "prompt", 33, 16, 7, "static", tmp_path / "o.mp4")
        rendered = render_workflow(client.workflow, client._values(request, "a.jpg"))
        assert not PLACEHOLDER.search(json.dumps(rendered))
        assert rendered["13"]["inputs"]["noise_seed"] == 7
        assert rendered["12"]["inputs"]["length"] == 33
        assert rendered["13"]["inputs"]["end_at_step"] == 3
        assert rendered["14"]["inputs"]["start_at_step"] == 3

    def test_finds_video_in_any_output_key(self):
        assert _find_video_output({"1": {"images": [{"filename": "a.png"}]}}) is None
        for key in ("videos", "gifs", "images"):
            found = _find_video_output({"1": {key: [{"filename": "a.mp4"}]}})
            assert found["filename"] == "a.mp4"


class FakeWS:
    def __init__(self, messages) -> None:  # noqa: ANN001
        self.messages = list(messages)
        self.closed = False

    def recv(self):  # noqa: ANN201
        if self.messages:
            return self.messages.pop(0)
        raise TimeoutError

    def close(self) -> None:
        self.closed = True


class FakeComfy:
    """ComfyUI 서버 흉내."""

    def __init__(self, history=None, prompt_status=200) -> None:  # noqa: ANN001
        self.requests: list[tuple[str, str]] = []
        self.prompt_body: dict | None = None
        self.freed = None
        self.upload_body = b""
        self.prompt_status = prompt_status
        self.history = history or {
            "status": {"status_str": "success", "completed": True},
            "outputs": {"18": {"images": [{"filename": "o.mp4", "subfolder": "vlog", "type": "output"}]}},
        }
        self.view_params = None

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append((request.method, request.url.path))
        path = request.url.path
        if path == "/upload/image":
            self.upload_body = request.content
            return httpx.Response(200, json={"name": "in.jpg", "subfolder": "", "type": "input"})
        if path == "/prompt":
            if self.prompt_status != 200:
                return httpx.Response(self.prompt_status, json={"error": "bad", "node_errors": {"3": "x"}})
            self.prompt_body = json.loads(request.content)
            return httpx.Response(200, json={"prompt_id": "pid", "number": 1, "node_errors": {}})
        if path == "/history/pid":
            return httpx.Response(200, json={"pid": self.history})
        if path == "/view":
            self.view_params = dict(request.url.params)
            return httpx.Response(200, content=b"video-bytes")
        if path == "/free":
            self.freed = json.loads(request.content)
            return httpx.Response(200)
        return httpx.Response(404)


def make_comfy(server, tmp_path, ws=None, settings=None):  # noqa: ANN001, ANN201
    workflow = tmp_path / "wf.json"
    workflow.write_text(
        json.dumps({"1": {"inputs": {"image": "{{IMAGE}}", "text": "{{PROMPT}}", "seed": "{{SEED}}"}}})
    )
    return ComfyUIVideo(
        "http://127.0.0.1:8188",
        settings or VideoSettings(timeout_sec=5),
        workflow,
        transport=httpx.MockTransport(server),
        ws_factory=(lambda _url, _timeout: ws) if ws is not None else None,
        sleep=lambda _s: None,
    )


def clip_request(tmp_path: Path) -> ClipRequest:
    image = tmp_path / "input.jpg"
    image.write_bytes(b"jpeg")
    return ClipRequest(image, "a cup", 33, 16, 9, "static", tmp_path / "out" / "c.mp4")


class TestComfyUI:
    def test_full_flow_with_polling(self, tmp_path):
        server = FakeComfy()
        client = make_comfy(server, tmp_path)
        out = client.generate(clip_request(tmp_path))
        assert out.read_bytes() == b"video-bytes"
        paths = [p for _, p in server.requests]
        assert paths == ["/upload/image", "/prompt", "/history/pid", "/view"]
        assert b'name="image"' in server.upload_body
        assert b'name="overwrite"' in server.upload_body
        body = server.prompt_body
        assert body["client_id"] == client.client_id
        assert body["prompt"]["1"]["inputs"] == {"image": "in.jpg", "text": "a cup", "seed": 9}
        assert server.view_params == {"filename": "o.mp4", "subfolder": "vlog", "type": "output"}

    def test_websocket_progress_then_finish(self, tmp_path):
        ws = FakeWS(
            [
                b"\x00preview",
                json.dumps({"type": "progress", "data": {"value": 1, "max": 6, "prompt_id": "pid"}}),
                json.dumps({"type": "executing", "data": {"node": None, "prompt_id": "pid"}}),
            ]
        )
        out = make_comfy(FakeComfy(), tmp_path, ws=ws).generate(clip_request(tmp_path))
        assert out.exists()
        assert ws.closed

    def test_execution_error_from_websocket(self, tmp_path):
        ws = FakeWS(
            [
                json.dumps(
                    {
                        "type": "execution_error",
                        "data": {"prompt_id": "pid", "node_type": "KSampler", "exception_message": "OOM"},
                    }
                )
            ]
        )
        with pytest.raises(VideoError, match="OOM"):
            make_comfy(FakeComfy(), tmp_path, ws=ws).generate(clip_request(tmp_path))
        assert ws.closed

    def test_other_prompts_errors_are_ignored(self, tmp_path):
        ws = FakeWS(
            [json.dumps({"type": "execution_error", "data": {"prompt_id": "other", "exception_message": "x"}})]
        )
        assert make_comfy(FakeComfy(), tmp_path, ws=ws).generate(clip_request(tmp_path)).exists()

    def test_error_in_history(self, tmp_path):
        server = FakeComfy(history={"status": {"status_str": "error", "messages": ["boom"]}, "outputs": {}})
        with pytest.raises(VideoError, match="boom"):
            make_comfy(server, tmp_path).generate(clip_request(tmp_path))

    def test_rejected_workflow_shows_node_errors(self, tmp_path):
        with pytest.raises(VideoError, match="node_errors"):
            make_comfy(FakeComfy(prompt_status=400), tmp_path).generate(clip_request(tmp_path))

    def test_no_video_in_result(self, tmp_path):
        server = FakeComfy(history={"status": {"completed": True}, "outputs": {"9": {"images": [{"filename": "a.png"}]}}})
        with pytest.raises(VideoError, match="영상이 없습니다"):
            make_comfy(server, tmp_path).generate(clip_request(tmp_path))

    def test_times_out(self, tmp_path):
        server = FakeComfy(history={"status": {"completed": False}, "outputs": {}})
        client = make_comfy(server, tmp_path, settings=VideoSettings(timeout_sec=0.05))
        with pytest.raises(VideoError, match="시간 초과"):
            client.generate(clip_request(tmp_path))

    def test_unreachable_server_is_video_error(self, tmp_path):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("refused", request=request)

        with pytest.raises(VideoError, match="통신 실패"):
            make_comfy(handler, tmp_path).generate(clip_request(tmp_path))

    def test_release_frees_models(self, tmp_path):
        server = FakeComfy()
        make_comfy(server, tmp_path).release()
        assert server.freed == {"unload_models": True, "free_memory": True}

    def test_missing_workflow_is_error(self, tmp_path):
        with pytest.raises(VideoError, match="워크플로 파일"):
            ComfyUIVideo("http://x", VideoSettings(), tmp_path / "none.json")


class TestKenBurnsFilter:
    @pytest.mark.parametrize(
        ("move", "expected"),
        [
            ("static", "z='1'"),
            ("slow_push_in", "z='1+0.120*on/89'"),
            ("pull_out", "z='1.120-0.120*on/89'"),
            ("pan_left", "x='(iw-iw/zoom)*(1-on/89)'"),
            ("pan_right", "x='(iw-iw/zoom)*on/89'"),
            ("tilt_up", "y='(ih-ih/zoom)*(1-on/89)'"),
            ("handheld", "sin(on/17)"),
        ],
    )
    def test_follows_camera_move(self, move, expected):
        graph = zoompan_filter(move, 90, 30, (1080, 1920))
        assert expected in graph
        assert "d=90" in graph
        assert "s=1080x1920" in graph
