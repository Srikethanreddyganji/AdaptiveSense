import numpy as np
import pytest
import sys
import threading
import time
from types import SimpleNamespace
from services.voice_service import VoiceService


def create_sine_wave(freq_hz: float, duration_sec: float, sample_rate: int = 16000, amplitude: float = 0.5) -> np.ndarray:
    t = np.linspace(0, duration_sec, int(sample_rate * duration_sec), endpoint=False)
    return (amplitude * np.sin(2 * np.pi * freq_hz * t)).astype(np.float32)


def test_extract_acoustic_features_sine_wave():
    service = VoiceService()
    sample_rate = 16000
    duration = 1.0
    sine = create_sine_wave(freq_hz=200.0, duration_sec=duration, sample_rate=sample_rate)

    features = service.extract_acoustic_features(sine, sample_rate)

    assert features["status"] == "ok"
    assert features["duration_seconds"] == pytest.approx(1.0, rel=1e-2)
    assert features["sample_rate_hz"] == 16000
    assert features["rms_energy"] > 0.1
    assert features["peak_amplitude"] > 0.4
    assert features["zero_crossing_rate"] > 0.01

    # Pitch detection for 200Hz tone
    assert features["mean_pitch_hz"] is not None
    assert 180.0 <= features["mean_pitch_hz"] <= 220.0
    assert "pitch_std_hz" in features
    assert "pitch_variance_hz" in features
    assert "speaking_rate" in features
    assert "intensity_variation_db" in features


def test_extract_acoustic_features_with_pauses():
    service = VoiceService()
    sample_rate = 16000
    # 0.5s tone, 0.4s silence, 0.5s tone
    tone = create_sine_wave(freq_hz=220.0, duration_sec=0.5, sample_rate=sample_rate)
    silence = np.zeros(int(sample_rate * 0.4), dtype=np.float32)
    audio = np.concatenate([tone, silence, tone])

    features = service.extract_acoustic_features(audio, sample_rate)

    assert features["status"] == "ok"
    assert features["pause_count"] >= 1
    assert features["total_pause_duration_seconds"] > 0.1
    assert features["average_pause_duration_seconds"] > 0.05
    assert features["pause_frequency_per_minute"] > 0.0
    assert features["pause_ratio"] > 0.1


def test_extract_acoustic_features_empty():
    service = VoiceService()
    empty_samples = np.array([], dtype=np.float32)
    features = service.extract_acoustic_features(empty_samples, 16000)
    assert features["status"] == "empty"
    assert features["duration_seconds"] == 0.0


def test_voice_service_analyze_handles_empty():
    service = VoiceService()
    empty_samples = np.array([], dtype=np.float32)
    result = service.analyze(empty_samples, 16000)
    assert result["status"] == "failed"
    assert result["error"] == "empty_audio"


def test_disabled_emotion_model_still_returns_real_features_and_stage_timings():
    service = VoiceService()
    service.enabled = False
    samples = create_sine_wave(200.0, 1.0)

    result = service.analyze(samples, 16000)

    assert result["status"] == "unavailable"
    assert result["acoustic_features"]["status"] == "ok"
    assert 180 <= result["acoustic_features"]["mean_pitch_hz"] <= 220
    assert result["timings_ms"]["acoustic_features"] >= 0
    assert result["timings_ms"]["total"] >= result["timings_ms"]["acoustic_features"]


def test_concurrent_emotion_model_requests_wait_for_one_cached_load(monkeypatch):
    calls = {"extractor": 0, "model": 0}

    class FakeExtractor:
        @classmethod
        def from_pretrained(cls, _model_id, **_kwargs):
            calls["extractor"] += 1
            time.sleep(0.05)
            return cls()

    class FakeModel:
        config = SimpleNamespace(id2label={0: "neutral"})

        @classmethod
        def from_pretrained(cls, _model_id, **_kwargs):
            calls["model"] += 1
            time.sleep(0.05)
            return cls()

        def eval(self):
            return self

    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(
        Wav2Vec2FeatureExtractor=FakeExtractor,
        Wav2Vec2ForSequenceClassification=FakeModel,
    ))
    service = VoiceService()
    barrier = threading.Barrier(4)
    errors = []

    def load():
        try:
            barrier.wait(timeout=2)
            service._load_model()
        except Exception as exc:  # pragma: no cover - assertion reports thread failure
            errors.append(exc)

    threads = [threading.Thread(target=load) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=3)

    assert not errors
    assert all(not thread.is_alive() for thread in threads)
    assert calls == {"extractor": 1, "model": 1}
    assert service._model is not None


def test_voice_model_can_load_only_from_local_cache(monkeypatch):
    calls = {}

    class FakeExtractor:
        @classmethod
        def from_pretrained(cls, _model_id, **kwargs):
            calls["extractor_local"] = kwargs.get("local_files_only")
            return cls()

    class FakeModel:
        config = SimpleNamespace(id2label={0: "neutral"})

        @classmethod
        def from_pretrained(cls, _model_id, **kwargs):
            calls["model_local"] = kwargs.get("local_files_only")
            return cls()

        def eval(self):
            return self

    monkeypatch.setenv("VOICE_EMOTION_LOCAL_FILES_ONLY", "true")
    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(
        Wav2Vec2FeatureExtractor=FakeExtractor,
        Wav2Vec2ForSequenceClassification=FakeModel,
    ))

    service = VoiceService()
    service._load_model()

    assert calls == {"extractor_local": True, "model_local": True}
