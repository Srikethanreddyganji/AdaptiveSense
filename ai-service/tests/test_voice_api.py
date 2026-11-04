import io
import subprocess
import threading
import wave
import numpy as np
import pytest
from fastapi.testclient import TestClient
from main import app
import main
from services import audio_utils


def make_test_wav(duration_sec: float = 1.0, freq_hz: float = 260.0) -> bytes:
    t = np.linspace(0, duration_sec, int(16000 * duration_sec), endpoint=False)
    samples = (0.5 * np.sin(2 * np.pi * freq_hz * t) * 32767).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(16000)
        wf.writeframes(samples.tobytes())
    return buf.getvalue()


client = TestClient(app)


@pytest.fixture(autouse=True)
def mock_transcription_and_voice(monkeypatch):
    # Avoid downloading 500MB models from HuggingFace during unit tests
    def mock_transcribe(samples, sample_rate):
        return {"text": "I am feeling a bit tired but hopeful today.", "status": "ok", "model_id": "test-mock-whisper"}

    monkeypatch.setattr(main.transcription_service, "transcribe", mock_transcribe)
    monkeypatch.setattr(main.voice_service, "enabled", False)


def test_analyze_voice_success_with_transcript():
    audio_data = make_test_wav(duration_sec=1.0)
    response = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", audio_data, "audio/wav")},
        data={"transcript": "I am feeling a bit tired but hopeful today."}
    )
    assert response.status_code == 200
    data = response.json()

    assert data["input_mode"] == "voice"
    assert data["text"] == "I am feeling a bit tired but hopeful today."
    assert "acoustic_features" in data
    assert data["acoustic_features"]["status"] == "ok"
    assert "rms_energy" in data["acoustic_features"]
    assert "pause_count" in data["acoustic_features"]
    assert "speaking_rate" in data["acoustic_features"]
    assert "social_cues" in data
    assert "nlp" in data
    assert data["transcription"]["status"] == "client"
    assert data["analysis_status"] == "partial"
    assert data["analysis_warnings"] == ["VOICE_EMOTION_UNAVAILABLE"]
    assert "timings_ms" in data
    assert data["timings_ms"]["voice_analysis"] >= 0


def test_analyze_browser_webm_upload_returns_real_acoustic_features():
    ffmpeg = audio_utils._find_ffmpeg()
    if not ffmpeg:
        pytest.skip("FFmpeg is required to exercise browser WebM uploads")
    encoded = subprocess.run(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "sine=frequency=250:sample_rate=48000",
            "-t", "1", "-c:a", "libopus", "-f", "webm", "pipe:1",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=20,
    )
    if encoded.returncode != 0:
        pytest.skip("This FFmpeg build does not include the Opus encoder")

    response = client.post(
        "/analyze-voice",
        files={"audio": ("recording.webm", encoded.stdout, "audio/webm;codecs=opus")},
        data={"transcript": "A test transcript supplied by browser recognition."},
    )
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["input_mode"] == "voice"
    assert result["acoustic_features"]["status"] == "ok"
    assert result["acoustic_features"]["sample_rate_hz"] == 16000
    assert result["acoustic_features"]["rms_energy"] > 0


def test_analyze_voice_reuses_client_transcript_without_whisper(monkeypatch):
    def unexpected_transcribe(*_args):
        raise AssertionError("Whisper should be skipped when a client transcript is available")

    monkeypatch.setattr(main.transcription_service, "transcribe", unexpected_transcribe)
    response = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", make_test_wav(), "audio/wav")},
        data={"transcript": "I am taking a pause between thoughts."},
    )
    assert response.status_code == 200
    assert response.json()["transcription"]["status"] == "client"
    assert response.json()["text"] == "I am taking a pause between thoughts."


def test_transcription_and_voice_analysis_run_concurrently(monkeypatch):
    barrier = threading.Barrier(2)

    def concurrent_transcribe(_samples, _sample_rate):
        barrier.wait(timeout=3)
        return {"text": "A concurrent transcription.", "status": "ok", "model_id": "test"}

    def concurrent_voice(_samples, _sample_rate):
        barrier.wait(timeout=3)
        features = {"status": "ok", "duration_seconds": 1.0}
        return {"status": "unavailable", "acoustic_features": features}

    monkeypatch.setattr(main.transcription_service, "transcribe", concurrent_transcribe)
    monkeypatch.setattr(main.voice_service, "analyze", concurrent_voice)
    response = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", make_test_wav(), "audio/wav")},
    )
    assert response.status_code == 200
    assert response.json()["text"] == "A concurrent transcription."


def test_analyze_voice_empty_audio():
    response = client.post(
        "/analyze-voice",
        files={"audio": ("empty.wav", b"", "audio/wav")},
        data={"transcript": "hello"}
    )
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "AUDIO_DECODING_FAILURE"


def test_voice_request_summary_logs_correlation_for_validation_failure(caplog):
    response = client.post(
        "/analyze-voice",
        files={"audio": ("empty.wav", b"", "audio/wav")},
        headers={"X-Request-ID": "timing-check-123"},
    )

    assert response.status_code == 400
    assert any(
        "voice_request_summary request_id=timing-check-123 status=400" in record.message
        for record in caplog.records
    )


def test_analyze_voice_unsupported_format():
    response = client.post(
        "/analyze-voice",
        files={"audio": ("bad.exe", b"not-audio-bytes-at-all", "application/x-dosexec")},
        data={"transcript": "hello"}
    )
    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "AUDIO_DECODING_FAILURE"


def test_analyze_voice_reports_transcription_model_load_failure(monkeypatch):
    monkeypatch.setattr(main.transcription_service, "transcribe", lambda *_: {
        "text": "", "status": "failed", "error": "transcription_model_unavailable",
        "model_id": "test-whisper",
    })
    response = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", make_test_wav(), "audio/wav")},
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "MODEL_LOADING_FAILURE"


def test_analyze_voice_reports_voice_analysis_failure(monkeypatch):
    monkeypatch.setattr(main.voice_service, "analyze", lambda *_: (_ for _ in ()).throw(RuntimeError("private detail")))
    response = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", make_test_wav(), "audio/wav")},
        data={"transcript": "A harmless test transcript."},
    )
    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "VOICE_ANALYSIS_FAILURE"
    assert "private detail" not in response.text


def test_analyze_voice_rejects_oversized_upload_before_full_read(monkeypatch):
    monkeypatch.setattr(main, "MAX_AUDIO_BYTES", 32)
    response = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", b"x" * 33, "audio/wav")},
        data={"transcript": "A harmless test transcript."},
    )
    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "RESOURCE_EXHAUSTION"


def test_internal_key_authorization(monkeypatch):
    monkeypatch.setattr(main, "INTERNAL_KEY", "secret-test-key")
    monkeypatch.setattr(main, "ENVIRONMENT", "development")

    audio_data = make_test_wav(duration_sec=1.0)

    # Missing/wrong key -> 401
    resp_unauth = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", audio_data, "audio/wav")},
        data={"transcript": "testing auth"},
        headers={"X-Internal-Key": "wrong-key"}
    )
    assert resp_unauth.status_code == 401
    assert resp_unauth.json()["detail"]["code"] == "INTERNAL_AUTHENTICATION_FAILURE"

    # Correct key -> 200
    resp_auth = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", audio_data, "audio/wav")},
        data={"transcript": "testing auth"},
        headers={"X-Internal-Key": "secret-test-key"}
    )
    assert resp_auth.status_code == 200


def test_production_fail_closed_if_key_missing(monkeypatch):
    monkeypatch.setattr(main, "ENVIRONMENT", "production")
    monkeypatch.setattr(main, "INTERNAL_KEY", "")

    audio_data = make_test_wav(duration_sec=1.0)
    resp = client.post(
        "/analyze-voice",
        files={"audio": ("sample.wav", audio_data, "audio/wav")},
        data={"transcript": "testing production fail closed"}
    )
    assert resp.status_code == 500
    assert resp.json()["detail"]["code"] == "INTERNAL_AUTHENTICATION_FAILURE"
