import io
import io
import os
import subprocess
import wave
import numpy as np
import pytest
from services import audio_utils
from services.audio_utils import (
    AudioValidationError,
    validate_audio_upload,
    load_mono_audio,
    MAX_AUDIO_BYTES,
)


def make_wav_bytes(duration_sec: float, sample_rate: int = 16000, freq_hz: float = 300.0) -> bytes:
    t = np.linspace(0, duration_sec, int(sample_rate * duration_sec), endpoint=False)
    samples = (0.5 * np.sin(2 * np.pi * freq_hz * t) * 32767).astype(np.int16)

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(samples.tobytes())
    return buffer.getvalue()


def test_validate_empty_audio():
    with pytest.raises(AudioValidationError) as exc_info:
        validate_audio_upload(b"", "test.wav", "audio/wav")
    assert exc_info.value.status_code == 400
    assert "empty" in str(exc_info.value).lower()


def test_validate_oversized_audio():
    large_data = b"0" * (MAX_AUDIO_BYTES + 1024)
    with pytest.raises(AudioValidationError) as exc_info:
        validate_audio_upload(large_data, "test.wav", "audio/wav")
    assert exc_info.value.status_code == 413


def test_validate_unsupported_format():
    with pytest.raises(AudioValidationError) as exc_info:
        validate_audio_upload(b"some data", "test.exe", "application/x-msdownload")
    assert exc_info.value.status_code == 400
    assert "unsupported" in str(exc_info.value).lower()


@pytest.mark.parametrize(
    ("filename", "content_type"),
    [
        ("recording.exe", "audio/webm"),
        ("recording.webm", "application/x-msdownload"),
    ],
)
def test_validate_rejects_mismatched_or_unsafe_metadata(filename, content_type):
    with pytest.raises(AudioValidationError) as exc_info:
        validate_audio_upload(b"not decoded here", filename, content_type)
    assert exc_info.value.status_code == 400
    assert "unsupported" in str(exc_info.value).lower()


def test_load_mono_audio_valid():
    wav_bytes = make_wav_bytes(duration_sec=1.0, sample_rate=16000, freq_hz=250.0)
    samples, sr = load_mono_audio(wav_bytes, "test.wav", "audio/wav")
    assert sr == 16000
    assert len(samples) == 16000
    assert isinstance(samples, np.ndarray)
    assert np.max(np.abs(samples)) > 0.1


def test_load_mono_audio_too_short():
    short_wav = make_wav_bytes(duration_sec=0.1, sample_rate=16000, freq_hz=250.0)
    with pytest.raises(AudioValidationError) as exc_info:
        load_mono_audio(short_wav, "short.wav", "audio/wav")
    assert exc_info.value.status_code == 400
    assert "too short" in str(exc_info.value).lower()


def test_load_mono_audio_silent():
    # 1 second of all zeros
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(16000)
        wf.writeframes(np.zeros(16000, dtype=np.int16).tobytes())
    silent_wav = buffer.getvalue()

    with pytest.raises(AudioValidationError) as exc_info:
        load_mono_audio(silent_wav, "silent.wav", "audio/wav")
    assert exc_info.value.status_code == 400
    assert "usable sound" in str(exc_info.value).lower()


def test_find_ffmpeg_uses_configured_executable(tmp_path, monkeypatch):
    executable = tmp_path / ("ffmpeg.exe" if os.name == "nt" else "ffmpeg")
    executable.write_bytes(b"test executable path")
    monkeypatch.setenv("FFMPEG_PATH", str(executable))
    assert audio_utils._find_ffmpeg() == str(executable)


def test_load_mono_audio_browser_webm_when_ffmpeg_is_available():
    ffmpeg = audio_utils._find_ffmpeg()
    if not ffmpeg:
        pytest.skip("FFmpeg is required to exercise browser WebM decoding")

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

    samples, sample_rate = load_mono_audio(
        encoded.stdout,
        "recording.webm",
        "audio/webm;codecs=opus",
    )
    assert sample_rate == 16000
    assert samples.size >= 15000
    assert float(np.max(np.abs(samples))) > 0.1
