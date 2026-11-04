import io
import os
import subprocess
import tempfile
import wave

import numpy as np

MAX_AUDIO_BYTES = int(os.getenv("MAX_AUDIO_BYTES", str(10 * 1024 * 1024)))
MAX_AUDIO_SECONDS = float(os.getenv("MAX_AUDIO_SECONDS", "60"))
TARGET_SAMPLE_RATE = 16000

ALLOWED_EXTENSIONS = {
    ".wav",
    ".webm",
    ".mp3",
    ".mp4",
    ".m4a",
    ".ogg",
    ".oga",
    ".flac",
    ".mpeg",
}

ALLOWED_CONTENT_TYPES = {
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/webm",
    "audio/mp3",
    "audio/mpeg",
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    "audio/ogg",
    "audio/flac",
    "video/webm",
    "application/octet-stream",
}


class AudioValidationError(ValueError):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.status_code = status_code


def _extension(filename: str | None) -> str:
    if not filename or "." not in filename:
        return ""
    return os.path.splitext(filename)[1].lower()


def validate_audio_upload(
        data: bytes,
        filename: str | None,
        content_type: str | None) -> None:

    if data is None or len(data) == 0:
        raise AudioValidationError("Audio file is empty.")

    if len(data) > MAX_AUDIO_BYTES:
        raise AudioValidationError(
            "Audio file is too large.",
            status_code=413
        )

    extension = _extension(filename)
    normalized_type = (content_type or "").split(";")[0].strip().lower()

    type_ok = (
        not normalized_type
        or normalized_type in ALLOWED_CONTENT_TYPES
        or normalized_type.startswith("audio/")
    )
    extension_ok = not extension or extension in ALLOWED_EXTENSIONS

    if not type_ok and not extension_ok:
        raise AudioValidationError(
            "Unsupported audio format. Use webm, wav, mp3, m4a, ogg, or flac."
        )


def _read_wav_bytes(data: bytes) -> tuple[np.ndarray, int]:
    with wave.open(io.BytesIO(data), "rb") as wav_file:
        channels = wav_file.getnchannels()
        sample_width = wav_file.getsampwidth()
        sample_rate = wav_file.getframerate()
        frames = wav_file.readframes(wav_file.getnframes())

    if sample_width == 1:
        samples = np.frombuffer(frames, dtype=np.uint8).astype(np.float32)
        samples = (samples - 128.0) / 128.0
    elif sample_width == 2:
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32)
        samples = samples / 32768.0
    else:
        raise AudioValidationError("Unsupported WAV sample width.")

    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)

    return samples, sample_rate


def _resample(samples: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate or samples.size == 0:
        return samples.astype(np.float32)

    duration = samples.size / float(source_rate)
    target_length = max(1, int(round(duration * target_rate)))
    source_times = np.linspace(0.0, duration, num=samples.size, endpoint=False)
    target_times = np.linspace(0.0, duration, num=target_length, endpoint=False)
    resampled = np.interp(target_times, source_times, samples)
    return resampled.astype(np.float32)


def _decode_with_librosa(data: bytes) -> tuple[np.ndarray, int] | None:
    try:
        import librosa
        import soundfile as sf
    except Exception:
        return None

    try:
        samples, sample_rate = sf.read(io.BytesIO(data), always_2d=False)
        if getattr(samples, "ndim", 1) > 1:
            samples = np.mean(samples, axis=1)
        return samples.astype(np.float32), int(sample_rate)
    except Exception:
        pass

    try:
        with tempfile.NamedTemporaryFile(suffix=".audio", delete=True) as temp_file:
            temp_file.write(data)
            temp_file.flush()
            samples, sample_rate = librosa.load(
                temp_file.name,
                sr=None,
                mono=True
            )
            return samples.astype(np.float32), int(sample_rate)
    except Exception:
        return None


def _decode_with_ffmpeg(data: bytes) -> tuple[np.ndarray, int] | None:
    try:
        process = subprocess.run(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                "pipe:0",
                "-ac",
                "1",
                "-ar",
                str(TARGET_SAMPLE_RATE),
                "-f",
                "wav",
                "pipe:1",
            ],
            input=data,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except FileNotFoundError:
        return None
    except Exception:
        return None

    if process.returncode != 0 or not process.stdout:
        return None

    try:
        samples, sample_rate = _read_wav_bytes(process.stdout)
        return samples, sample_rate
    except Exception:
        return None


def load_mono_audio(
        data: bytes,
        filename: str | None = None,
        content_type: str | None = None,
        target_sr: int = TARGET_SAMPLE_RATE) -> tuple[np.ndarray, int]:

    validate_audio_upload(data, filename, content_type)

    samples = None
    sample_rate = None

    extension = _extension(filename)
    if extension == ".wav" or (content_type or "").startswith("audio/wav"):
        try:
            samples, sample_rate = _read_wav_bytes(data)
        except Exception:
            samples = None

    if samples is None:
        decoded = _decode_with_librosa(data)
        if decoded is not None:
            samples, sample_rate = decoded

    if samples is None:
        decoded = _decode_with_ffmpeg(data)
        if decoded is not None:
            samples, sample_rate = decoded

    if samples is None:
        raise AudioValidationError(
            "Could not decode the audio file. Convert it to WAV or install ffmpeg."
        )

    samples = np.asarray(samples, dtype=np.float32).flatten()
    sample_rate = int(sample_rate)

    if samples.size == 0 or not np.any(np.abs(samples) > 1e-7):
        raise AudioValidationError("Audio contains no usable sound.")

    duration = samples.size / float(sample_rate) if sample_rate else 0.0
    if duration > MAX_AUDIO_SECONDS:
        raise AudioValidationError(
            "Audio recording is too long.",
            status_code=413
        )

    if sample_rate != target_sr:
        samples = _resample(samples, sample_rate, target_sr)
        sample_rate = target_sr

    peak = float(np.max(np.abs(samples))) if samples.size else 0.0
    if peak > 1.0:
        samples = samples / peak

    return samples, sample_rate
