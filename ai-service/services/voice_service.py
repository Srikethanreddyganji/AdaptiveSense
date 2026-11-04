import os
import threading

import numpy as np
import torch


VOICE_LABEL_MAP = {
    "neu": "neutral",
    "neutral": "neutral",
    "hap": "joy",
    "happy": "joy",
    "happiness": "joy",
    "ang": "anger",
    "angry": "anger",
    "anger": "anger",
    "sad": "sadness",
    "sadness": "sadness",
}


class VoiceService:

    def __init__(self):
        self._lock = threading.Lock()
        self._model = None
        self._feature_extractor = None
        self._id2label = {}
        self._load_attempted = False
        self._load_error = None
        self.enabled = os.getenv(
            "VOICE_EMOTION_ENABLED",
            "true"
        ).strip().lower() in {"1", "true", "yes", "on"}
        self.model_id = os.getenv(
            "VOICE_EMOTION_MODEL",
            "superb/wav2vec2-base-superb-er"
        )
        self.expected_sample_rate = 16000

    def _load_model(self):
        if not self.enabled:
            self._load_error = "voice_emotion_disabled"
            return

        if self._model is not None or self._load_attempted:
            return

        with self._lock:
            if self._model is not None or self._load_attempted:
                return

            self._load_attempted = True

            try:
                from transformers import (
                    Wav2Vec2FeatureExtractor,
                    Wav2Vec2ForSequenceClassification,
                )

                torch.set_num_threads(1)
                self._feature_extractor = (
                    Wav2Vec2FeatureExtractor.from_pretrained(
                        self.model_id
                    )
                )
                self._model = Wav2Vec2ForSequenceClassification.from_pretrained(
                    self.model_id,
                    low_cpu_mem_usage=True
                )
                self._model.eval()
                self._id2label = {
                    int(key): value
                    for key, value in self._model.config.id2label.items()
                }
                self._load_error = None
            except Exception as exc:
                self._model = None
                self._feature_extractor = None
                self._load_error = "voice_emotion_model_unavailable"
                print(
                    f"Warning: voice emotion model could not be loaded ({type(exc).__name__})."
                )

    def extract_acoustic_features(
            self,
            samples: np.ndarray,
            sample_rate: int) -> dict:

        audio = np.asarray(samples, dtype=np.float32).flatten()
        if audio.size == 0 or sample_rate <= 0:
            return {
                "status": "empty",
                "duration_seconds": 0.0,
            }

        duration = float(audio.size / sample_rate)
        rms_energy = float(np.sqrt(np.mean(np.square(audio))))
        peak = float(np.max(np.abs(audio)))

        frame_length = max(1, int(sample_rate * 0.03))
        hop = max(1, int(sample_rate * 0.015))
        if audio.size < frame_length:
            frames = audio.reshape(1, -1)
        else:
            n_frames = 1 + (audio.size - frame_length) // hop
            frames = np.lib.stride_tricks.as_strided(
                audio,
                shape=(n_frames, frame_length),
                strides=(audio.strides[0] * hop, audio.strides[0]),
                writeable=False,
            )

        frame_rms = np.sqrt(np.mean(np.square(frames), axis=1) + 1e-12)
        silence_threshold = max(0.01, float(np.median(frame_rms) * 0.25))
        silent_frames = frame_rms < silence_threshold
        silence_ratio = float(np.mean(silent_frames)) if silent_frames.size else 0.0
        pause_count = self._count_runs(silent_frames, min_run=3)

        voiced = audio[np.abs(audio) > silence_threshold]
        zero_crossings = np.mean(np.abs(np.diff(np.sign(audio)))) / 2.0
        zcr = float(zero_crossings)

        pitch_mean, pitch_std = self._estimate_pitch(audio, sample_rate)
        spectral_centroid = self._spectral_centroid(audio, sample_rate)
        speaking_rate = self._approximate_speaking_rate(
            audio,
            sample_rate,
            silence_ratio
        )

        return {
            "status": "ok",
            "duration_seconds": round(duration, 4),
            "sample_rate_hz": int(sample_rate),
            "rms_energy": round(rms_energy, 6),
            "peak_amplitude": round(peak, 6),
            "silence_ratio": round(silence_ratio, 4),
            "pause_count": int(pause_count),
            "mean_pitch_hz": pitch_mean,
            "pitch_std_hz": pitch_std,
            "speaking_rate_approx_syl_per_sec": speaking_rate,
            "spectral_centroid_hz": spectral_centroid,
            "zero_crossing_rate": round(zcr, 6),
            "voiced_ratio": round(
                float(voiced.size / audio.size) if audio.size else 0.0,
                4
            ),
        }

    def analyze(self, samples: np.ndarray, sample_rate: int) -> dict:
        features = self.extract_acoustic_features(samples, sample_rate)

        if features.get("status") == "empty":
            return self._unavailable_result(
                "empty_audio",
                features
            )

        if not self.enabled:
            return self._unavailable_result(
                "disabled",
                features
            )

        self._load_model()

        if self._model is None or self._feature_extractor is None:
            return self._unavailable_result(
                self._load_error or "voice_emotion_model_unavailable",
                features
            )

        try:
            inputs = self._feature_extractor(
                np.asarray(samples, dtype=np.float32),
                sampling_rate=self.expected_sample_rate,
                return_tensors="pt",
                padding=True,
            )

            with torch.no_grad():
                logits = self._model(**inputs).logits
                probabilities = torch.softmax(logits, dim=-1)[0].tolist()

            scores = []
            for index, probability in enumerate(probabilities):
                raw_label = self._id2label.get(index, str(index))
                scores.append({
                    "label": VOICE_LABEL_MAP.get(
                        str(raw_label).lower(),
                        str(raw_label)
                    ),
                    "raw_label": str(raw_label),
                    "score": round(float(probability), 4),
                })

            scores = sorted(
                scores,
                key=lambda item: item["score"],
                reverse=True
            )

            if not scores:
                return self._unavailable_result(
                    "voice_emotion_empty_output",
                    features
                )

            top = scores[0]
            second = scores[1]["score"] if len(scores) > 1 else 0.0
            margin = float(top["score"]) - float(second)
            uncertainty = round(1.0 - float(top["score"]), 4)

            if top["score"] >= 0.7 and margin >= 0.2:
                confidence = "high"
            elif top["score"] >= 0.45:
                confidence = "medium"
            else:
                confidence = "low"

            return {
                "status": "ok",
                "label": top["label"],
                "score": top["score"],
                "scores": scores,
                "confidence": confidence,
                "uncertainty": uncertainty,
                "model_id": self.model_id,
                "model_labels": [
                    VOICE_LABEL_MAP.get(str(value).lower(), str(value))
                    for value in self._id2label.values()
                ],
                "expected_sample_rate_hz": self.expected_sample_rate,
                "licence": "apache-2.0",
                "language_notes": (
                    "Model trained on IEMOCAP English acted speech. "
                    "It does not cover every language or accent."
                ),
                "acoustic_features": features,
            }
        except Exception as exc:
            print(
                f"Warning: voice emotion inference failed ({type(exc).__name__})."
            )
            return self._unavailable_result(
                "voice_emotion_inference_failed",
                features
            )

    def _unavailable_result(self, reason: str, features: dict) -> dict:
        return {
            "status": "unavailable" if reason != "empty_audio" else "failed",
            "label": None,
            "score": None,
            "scores": [],
            "confidence": None,
            "uncertainty": None,
            "model_id": self.model_id,
            "error": reason,
            "acoustic_features": features,
            "licence": "apache-2.0",
            "language_notes": (
                "Model trained on IEMOCAP English acted speech. "
                "It does not cover every language or accent."
            ),
        }

    @staticmethod
    def _count_runs(mask: np.ndarray, min_run: int) -> int:
        if mask.size == 0:
            return 0
        padded = np.concatenate([[False], mask, [False]])
        changes = np.diff(padded.astype(int))
        starts = np.where(changes == 1)[0]
        ends = np.where(changes == -1)[0]
        return int(np.sum((ends - starts) >= min_run))

    @staticmethod
    def _estimate_pitch(samples: np.ndarray, sample_rate: int) -> tuple:
        if samples.size < sample_rate // 20:
            return None, None

        window = samples[: min(samples.size, sample_rate * 2)]
        min_hz = 60.0
        max_hz = 400.0
        min_lag = max(1, int(sample_rate / max_hz))
        max_lag = min(window.size - 1, int(sample_rate / min_hz))
        if max_lag <= min_lag:
            return None, None

        centered = window - np.mean(window)
        corr = np.correlate(centered, centered, mode="full")
        corr = corr[corr.size // 2:]
        segment = corr[min_lag:max_lag]
        if segment.size == 0:
            return None, None

        peak_index = int(np.argmax(segment)) + min_lag
        if corr[0] <= 0:
            return None, None

        mean_pitch = float(sample_rate / peak_index)
        if mean_pitch < min_hz or mean_pitch > max_hz:
            return None, None

        frame_length = max(min_lag * 4, int(sample_rate * 0.04))
        hop = max(1, frame_length // 2)
        pitches = []
        for start in range(0, samples.size - frame_length, hop):
            frame = samples[start:start + frame_length]
            frame = frame - np.mean(frame)
            frame_corr = np.correlate(frame, frame, mode="full")
            frame_corr = frame_corr[frame_corr.size // 2:]
            part = frame_corr[min_lag:max_lag]
            if part.size == 0 or frame_corr[0] <= 0:
                continue
            lag = int(np.argmax(part)) + min_lag
            value = sample_rate / lag
            if min_hz <= value <= max_hz:
                pitches.append(value)

        pitch_std = (
            round(float(np.std(pitches)), 2) if len(pitches) > 1 else None
        )
        return round(mean_pitch, 2), pitch_std

    @staticmethod
    def _spectral_centroid(samples: np.ndarray, sample_rate: int) -> float | None:
        if samples.size < 16:
            return None
        spectrum = np.abs(np.fft.rfft(samples * np.hanning(samples.size)))
        freqs = np.fft.rfftfreq(samples.size, d=1.0 / sample_rate)
        denom = float(np.sum(spectrum))
        if denom <= 0:
            return None
        centroid = float(np.sum(freqs * spectrum) / denom)
        return round(centroid, 2)

    @staticmethod
    def _approximate_speaking_rate(
            samples: np.ndarray,
            sample_rate: int,
            silence_ratio: float) -> float | None:
        duration = samples.size / float(sample_rate)
        if duration <= 0:
            return None

        envelope = np.abs(samples)
        threshold = max(0.02, float(np.mean(envelope) * 1.5))
        peaks = envelope > threshold
        padded = np.concatenate([[False], peaks, [False]])
        changes = np.diff(padded.astype(int))
        starts = np.where(changes == 1)[0]
        voiced_duration = max(0.01, duration * (1.0 - silence_ratio))
        approx_syllables = max(1, starts.size // 4)
        rate = approx_syllables / voiced_duration
        return round(float(rate), 3)
