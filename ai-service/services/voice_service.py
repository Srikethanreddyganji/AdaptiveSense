import os
import threading
import logging
import time

import numpy as np
import torch

logger = logging.getLogger(__name__)


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
        self._load_complete = threading.Event()
        self._inference_lock = threading.Lock()
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
        self.local_files_only = os.getenv(
            "VOICE_EMOTION_LOCAL_FILES_ONLY", "false"
        ).strip().lower() in {"1", "true", "yes", "on"}
        self.expected_sample_rate = 16000

    def _load_model(self):
        if not self.enabled:
            self._load_error = "voice_emotion_disabled"
            return

        if self._model is not None:
            return

        if self._load_attempted:
            self._load_complete.wait()
            return

        with self._lock:
            if self._model is not None:
                return
            if self._load_attempted:
                should_wait = True
            else:
                self._load_attempted = True
                should_wait = False

        if should_wait:
            self._load_complete.wait()
            return

        try:
            from transformers import (
                Wav2Vec2FeatureExtractor,
                Wav2Vec2ForSequenceClassification,
            )

            torch.set_num_threads(1)
            self._feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained(
                self.model_id,
                local_files_only=self.local_files_only,
            )
            self._model = Wav2Vec2ForSequenceClassification.from_pretrained(
                self.model_id,
                low_cpu_mem_usage=True,
                local_files_only=self.local_files_only,
            )
            self._model.eval()
            self._id2label = {
                int(key): value
                for key, value in self._model.config.id2label.items()
            }
            self._load_error = None
            logger.info("Wav2vec2 voice emotion model loaded successfully (model=%s).", self.model_id)
        except Exception as exc:
            self._model = None
            self._feature_extractor = None
            self._load_error = "voice_emotion_model_unavailable"
            logger.error("Wav2vec2 model load failed (model=%s, error=%s).", self.model_id, type(exc).__name__)
        finally:
            self._load_complete.set()

    def extract_acoustic_features(
            self,
            samples: np.ndarray,
            sample_rate: int) -> dict:

        audio = np.asarray(samples, dtype=np.float32).reshape(-1)
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
        hop_seconds = float(hop / sample_rate)

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

        pause_count, total_pause_duration, avg_pause_duration = self._measure_pauses(
            silent_frames,
            hop_seconds,
            min_run=3
        )
        pause_frequency_per_min = round(
            float((pause_count / duration) * 60.0) if duration > 0 else 0.0,
            2
        )

        voiced = audio[np.abs(audio) > silence_threshold]
        zero_crossings = np.mean(np.abs(np.diff(np.sign(audio)))) / 2.0
        zcr = float(zero_crossings)

        pitch_mean, pitch_std, pitch_var = self._estimate_pitch(audio, sample_rate)
        spectral_centroid = self._spectral_centroid(audio, sample_rate)
        speaking_rate = self._approximate_speaking_rate(
            audio,
            sample_rate,
            silence_ratio
        )

        energy_std = round(float(np.std(frame_rms)), 6)
        active_rms = frame_rms[~silent_frames]
        if active_rms.size > 0:
            active_db = 20.0 * np.log10(np.clip(active_rms, 1e-5, 1.0))
            intensity_variation_db = round(float(np.std(active_db)), 2)
        else:
            intensity_variation_db = 0.0

        return {
            "status": "ok",
            "duration_seconds": round(duration, 4),
            "sample_rate_hz": int(sample_rate),
            "mean_pitch_hz": pitch_mean,
            "pitch_std_hz": pitch_std,
            "pitch_variance_hz": pitch_var,
            "speaking_rate": speaking_rate,
            "speaking_rate_approx_syl_per_sec": speaking_rate,
            "speaking_rate_method": "smoothed RMS peaks as an estimated syllable-nucleus rate",
            "pause_count": int(pause_count),
            "total_pause_duration_seconds": total_pause_duration,
            "average_pause_duration_seconds": avg_pause_duration,
            "pause_frequency_per_minute": pause_frequency_per_min,
            "pause_ratio": round(silence_ratio, 4),
            "silence_ratio": round(silence_ratio, 4),
            "rms_energy": round(rms_energy, 6),
            "energy_variation": energy_std,
            "energy_std": energy_std,
            "intensity_variation_db": intensity_variation_db,
            "peak_amplitude": round(peak, 6),
            "zero_crossing_rate": round(zcr, 6),
            "spectral_centroid_hz": spectral_centroid,
            "voiced_ratio": round(
                float(voiced.size / audio.size) if audio.size else 0.0,
                4
            ),
        }

    def analyze(self, samples: np.ndarray, sample_rate: int) -> dict:
        analysis_started = time.perf_counter()
        feature_started = time.perf_counter()
        features = self.extract_acoustic_features(samples, sample_rate)
        feature_extraction_ms = round((time.perf_counter() - feature_started) * 1000, 2)
        if features.get("status") == "empty":
            result = self._unavailable_result(
                "empty_audio",
                features
            )
            result["timings_ms"] = {
                "acoustic_features": feature_extraction_ms,
                "model_load": 0.0,
                "preprocessing": 0.0,
                "inference": 0.0,
                "total": round((time.perf_counter() - analysis_started) * 1000, 2),
            }
            return result

        emotion = self.analyze_emotion(samples, sample_rate)
        emotion["acoustic_features"] = features
        emotion.setdefault("timings_ms", {})["acoustic_features"] = feature_extraction_ms
        emotion["timings_ms"]["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
        return emotion

    def analyze_emotion(self, samples: np.ndarray, sample_rate: int) -> dict:
        analysis_started = time.perf_counter()
        timings = {"model_load": 0.0, "preprocessing": 0.0, "inference": 0.0}
        if not self.enabled:
            result = self._unavailable_result("disabled", {})
            timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
            result["timings_ms"] = timings
            return result

        if sample_rate != self.expected_sample_rate:
            result = self._unavailable_result("unexpected_sample_rate", {})
            timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
            result["timings_ms"] = timings
            return result

        model_cached = self._model is not None or self._load_attempted
        model_load_started = time.perf_counter()
        self._load_model()
        timings["model_load"] = round((time.perf_counter() - model_load_started) * 1000, 2)
        if self._model is None or self._feature_extractor is None:
            result = self._unavailable_result(
                self._load_error or "voice_emotion_model_unavailable", {}
            )
            timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
            result["timings_ms"] = timings
            result["model_cached"] = model_cached
            return result

        audio = np.asarray(samples, dtype=np.float32).reshape(-1)
        segment_samples = self.expected_sample_rate * 4
        segments = [
            audio[start:start + segment_samples]
            for start in range(0, audio.size, segment_samples)
            if audio[start:start + segment_samples].size > 0
            and float(np.sqrt(np.mean(np.square(audio[start:start + segment_samples])))) > 1e-4
        ]
        if not segments and audio.size:
            # Preserve low-level speech rather than treating a quiet recording as silence.
            segments = [audio]
        if not segments:
            result = self._unavailable_result("empty_audio", {})
            timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
            result["timings_ms"] = timings
            return result

        probability_total = None
        weight_total = 0.0
        try:
            with self._inference_lock:
                for start in range(0, len(segments), 2):
                    batch = segments[start:start + 2]
                    preprocessing_started = time.perf_counter()
                    inputs = self._feature_extractor(
                        batch,
                        sampling_rate=self.expected_sample_rate,
                        return_tensors="pt",
                        padding=True,
                    )
                    timings["preprocessing"] += round(
                        (time.perf_counter() - preprocessing_started) * 1000, 2
                    )

                    inference_started = time.perf_counter()
                    with torch.inference_mode():
                        logits = self._model(**inputs).logits
                        batch_probabilities = torch.softmax(logits, dim=-1).cpu().numpy()
                    timings["inference"] += round(
                        (time.perf_counter() - inference_started) * 1000, 2
                    )
                    for index, probability in enumerate(batch_probabilities):
                        weight = float(segments[start + index].size)
                        if probability_total is None:
                            probability_total = np.zeros_like(probability, dtype=np.float64)
                        probability_total += np.asarray(probability, dtype=np.float64) * weight
                        weight_total += weight

            probabilities = probability_total / weight_total
            scores = []
            for index, probability in enumerate(probabilities.tolist()):
                raw_label = self._id2label.get(index, str(index))
                scores.append({
                    "label": VOICE_LABEL_MAP.get(str(raw_label).lower(), str(raw_label)),
                    "raw_label": str(raw_label),
                    "score": round(float(probability), 4),
                })
            scores.sort(key=lambda item: item["score"], reverse=True)
            if not scores:
                result = self._unavailable_result("voice_emotion_empty_output", {})
                timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
                result["timings_ms"] = timings
                return result

            top = scores[0]
            second = scores[1]["score"] if len(scores) > 1 else 0.0
            margin = float(top["score"]) - float(second)
            confidence = "high" if top["score"] >= 0.7 and margin >= 0.2 else (
                "medium" if top["score"] >= 0.45 else "low"
            )
            timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
            logger.info(
                "Voice emotion stages: model_load_ms=%.2f preprocessing_ms=%.2f "
                "inference_ms=%.2f total_ms=%.2f model_cached=%s segments=%d model=%s.",
                timings["model_load"], timings["preprocessing"], timings["inference"],
                timings["total"], model_cached, len(segments), self.model_id,
            )
            return {
                "status": "ok",
                "label": top["label"],
                "score": top["score"],
                "scores": scores,
                "confidence": confidence,
                "uncertainty": round(1.0 - float(top["score"]), 4),
                "model_id": self.model_id,
                "model_labels": [
                    VOICE_LABEL_MAP.get(str(value).lower(), str(value))
                    for value in self._id2label.values()
                ],
                "expected_sample_rate_hz": self.expected_sample_rate,
                "segment_count": len(segments),
                "segment_duration_seconds": 4,
                "licence": "apache-2.0",
                "language_notes": (
                    "Model trained on IEMOCAP English acted speech. "
                    "It does not cover every language or accent. Voice emotion is an uncertain signal."
                ),
                "timings_ms": timings,
                "model_cached": model_cached,
            }
        except Exception as exc:
            logger.error("Wav2vec2 inference failed (model=%s, error=%s).", self.model_id, type(exc).__name__)
            result = self._unavailable_result("voice_emotion_inference_failed", {})
            timings["total"] = round((time.perf_counter() - analysis_started) * 1000, 2)
            result["timings_ms"] = timings
            result["model_cached"] = model_cached
            return result

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
    def _measure_pauses(
            mask: np.ndarray,
            hop_seconds: float,
            min_run: int = 3) -> tuple[int, float, float]:
        if mask.size == 0:
            return 0, 0.0, 0.0
        padded = np.concatenate([[False], mask, [False]])
        changes = np.diff(padded.astype(int))
        starts = np.where(changes == 1)[0]
        ends = np.where(changes == -1)[0]
        lengths = ends - starts
        valid_runs = lengths[lengths >= min_run]
        pause_count = int(valid_runs.size)
        total_pause_frames = int(np.sum(valid_runs))
        total_pause_duration = round(float(total_pause_frames * hop_seconds), 4)
        avg_pause_duration = (
            round(float(total_pause_duration / pause_count), 4)
            if pause_count > 0
            else 0.0
        )
        return pause_count, total_pause_duration, avg_pause_duration

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
            return None, None, None

        min_hz = 60.0
        max_hz = 400.0
        min_lag = max(1, int(sample_rate / max_hz))
        frame_length = max(min_lag * 4, int(sample_rate * 0.04))
        hop = max(1, frame_length // 2)
        max_lag = min(frame_length - 1, int(sample_rate / min_hz))
        if max_lag <= min_lag:
            return None, None, None

        frame_count = 1 + max(0, (samples.size - frame_length) // hop)
        frames = np.lib.stride_tricks.as_strided(
            samples,
            shape=(frame_count, frame_length),
            strides=(samples.strides[0] * hop, samples.strides[0]),
            writeable=False,
        )
        # Compute pitch from every voiced frame. Batched FFT autocorrelation is
        # O(n log n) per frame and avoids the old repeated quadratic np.correlate.
        n_fft = 1 << (2 * frame_length - 2).bit_length()
        pitch_values = []
        for offset in range(0, frame_count, 256):
            batch = frames[offset:offset + 256]
            batch = batch - np.mean(batch, axis=1, keepdims=True)
            frame_energy = np.sum(np.square(batch), axis=1)
            voiced_mask = frame_energy > 1e-8
            if not np.any(voiced_mask):
                continue
            batch = batch[voiced_mask]
            spectrum = np.fft.rfft(batch, n=n_fft, axis=1)
            correlations = np.fft.irfft(spectrum * np.conjugate(spectrum), n=n_fft, axis=1)
            lag_candidates = correlations[:, min_lag:max_lag]
            best_lags = np.argmax(lag_candidates, axis=1) + min_lag
            values = sample_rate / best_lags
            pitch_values.extend(values[(values >= min_hz) & (values <= max_hz)].tolist())

        if not pitch_values:
            return None, None, None
        return (
            round(float(np.mean(pitch_values)), 2),
            round(float(np.std(pitch_values)), 2) if len(pitch_values) > 1 else None,
            round(float(np.var(pitch_values)), 2) if len(pitch_values) > 1 else None,
        )

    @staticmethod
    def _autocorrelation(samples: np.ndarray) -> np.ndarray:
        """Return non-negative lags using FFT convolution in O(n log n)."""
        n_fft = 1 << (2 * samples.size - 2).bit_length()
        spectrum = np.fft.rfft(samples, n=n_fft)
        return np.fft.irfft(spectrum * np.conjugate(spectrum), n=n_fft)[:samples.size]

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

        frame_length = max(1, int(sample_rate * 0.025))
        hop = max(1, int(sample_rate * 0.01))
        if samples.size < frame_length:
            envelope = np.asarray([np.sqrt(np.mean(np.square(samples)))])
        else:
            frame_count = 1 + (samples.size - frame_length) // hop
            frames = np.lib.stride_tricks.as_strided(
                samples,
                shape=(frame_count, frame_length),
                strides=(samples.strides[0] * hop, samples.strides[0]),
                writeable=False,
            )
            frame_rms = np.sqrt(np.mean(np.square(frames), axis=1) + 1e-12)
            envelope = np.convolve(frame_rms, np.ones(3, dtype=np.float32) / 3.0, mode="same")

        threshold = max(0.01, float(np.percentile(envelope, 40) * 1.15))
        if envelope.size >= 3:
            candidates = np.flatnonzero(
                (envelope[1:-1] >= envelope[:-2])
                & (envelope[1:-1] > envelope[2:])
                & (envelope[1:-1] >= threshold)
            ) + 1
        else:
            candidates = np.array([], dtype=np.int64)

        # Keep energy nuclei at least 120 ms apart. This is an estimate of
        # syllabic peaks, not an exact phonetic syllable recognizer.
        minimum_distance = max(1, int(0.12 * sample_rate / hop))
        chosen = []
        for index in sorted(candidates.tolist(), key=lambda item: envelope[item], reverse=True):
            if all(abs(index - previous) >= minimum_distance for previous in chosen):
                chosen.append(index)
        nucleus_count = max(1, len(chosen))
        voiced_duration = max(0.01, duration * (1.0 - silence_ratio))
        rate = nucleus_count / voiced_duration
        return round(float(rate), 3)
