import sys
import threading
import time
from types import SimpleNamespace

from services.transcription_service import TranscriptionService


def test_concurrent_requests_share_one_whisper_pipeline_load(monkeypatch):
    calls = []

    def fake_pipeline(*_args, **_kwargs):
        calls.append(1)
        time.sleep(0.05)
        return object()

    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(pipeline=fake_pipeline))
    # Keep this test focused on the one-time pipeline lock; the adjacent test
    # exercises local-only tokenizer and feature-extractor initialization.
    monkeypatch.setenv("WHISPER_LOCAL_FILES_ONLY", "false")
    service = TranscriptionService()
    barrier = threading.Barrier(4)
    errors = []

    def load():
        try:
            barrier.wait(timeout=2)
            service._load_pipeline()
        except Exception as exc:  # pragma: no cover - assertion reports thread failure
            errors.append(exc)

    threads = [threading.Thread(target=load) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=3)

    assert not errors
    assert all(not thread.is_alive() for thread in threads)
    assert len(calls) == 1
    assert service._pipeline is not None


def test_whisper_pipeline_uses_local_assets_when_configured(monkeypatch):
    calls = {}

    class FakeAsset:
        @classmethod
        def from_pretrained(cls, model_id, **kwargs):
            calls.setdefault("assets", []).append((model_id, kwargs.get("local_files_only")))
            return cls()

    def fake_pipeline(*args, **kwargs):
        calls["pipeline"] = (args, kwargs)
        return object()

    monkeypatch.setenv("WHISPER_LOCAL_FILES_ONLY", "true")
    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(
        pipeline=fake_pipeline,
        AutoFeatureExtractor=FakeAsset,
        AutoTokenizer=FakeAsset,
    ))
    service = TranscriptionService()

    service._load_pipeline()

    assert calls["assets"] == [
        (service.model_id, True),
        (service.model_id, True),
    ]
    _args, pipeline_options = calls["pipeline"]
    assert pipeline_options["local_files_only"] is True
    assert "local_files_only" not in pipeline_options["model_kwargs"]
    assert service._pipeline is not None


def test_whisper_defaults_to_local_cache_only(monkeypatch):
    monkeypatch.delenv("WHISPER_LOCAL_FILES_ONLY", raising=False)
    service = TranscriptionService()
    assert service.local_files_only is True
