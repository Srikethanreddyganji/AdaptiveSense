import os
import threading
import logging
import time

import numpy as np
import torch
from services.runtime_config import configure_torch_threads

logger = logging.getLogger(__name__)


class TranscriptionService:

    def __init__(self):
        self._lock = threading.Lock()
        self._load_complete = threading.Event()
        self._inference_lock = threading.Lock()
        self._pipeline = None
        self._load_attempted = False
        self._load_error = None
        self.model_id = os.getenv(
            "WHISPER_MODEL",
            "openai/whisper-tiny"
        )
        self.local_files_only = os.getenv(
            "WHISPER_LOCAL_FILES_ONLY", "true"
        ).strip().lower() in {"1", "true", "yes", "on"}

    def _load_pipeline(self):
        if self._pipeline is not None:
            return

        if self._load_attempted:
            self._load_complete.wait()
            return

        with self._lock:
            if self._pipeline is not None:
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
            from transformers import pipeline

            configure_torch_threads(torch)
            pipeline_options = {
                "model_kwargs": {
                    "low_cpu_mem_usage": True,
                },
            }
            if self.local_files_only:
                from transformers import AutoFeatureExtractor, AutoTokenizer

                # `local_files_only` is a pipeline/Hub option in the installed
                # Transformers version. Nesting it in model_kwargs passes it a
                # second time to AutoConfig and raises TypeError.
                pipeline_options["local_files_only"] = True
                pipeline_options["tokenizer"] = AutoTokenizer.from_pretrained(
                    self.model_id, local_files_only=True
                )
                pipeline_options["feature_extractor"] = AutoFeatureExtractor.from_pretrained(
                    self.model_id, local_files_only=True
                )
            self._pipeline = pipeline(
                "automatic-speech-recognition",
                model=self.model_id,
                device=-1,
                **pipeline_options,
            )
            self._load_error = None
            logger.info("Whisper model loaded successfully (model=%s).", self.model_id)
        except Exception as exc:
            self._pipeline = None
            self._load_error = "transcription_model_unavailable"
            logger.error("Whisper model load failed (model=%s, error=%s).", self.model_id, type(exc).__name__)
        finally:
            self._load_complete.set()

    def transcribe(self, samples: np.ndarray, sample_rate: int) -> dict:
        model_cached = self._pipeline is not None or self._load_attempted
        load_started = time.perf_counter()
        if samples is None or samples.size == 0:
            return {
                "text": "",
                "status": "empty",
                "model_id": self.model_id,
            }

        self._load_pipeline()
        model_load_ms = round((time.perf_counter() - load_started) * 1000, 2)

        if self._pipeline is None:
            return {
                "text": "",
                "status": "failed",
                "model_id": self.model_id,
                "error": self._load_error or "transcription_unavailable",
                "timings_ms": {"model_load": model_load_ms, "inference": 0.0, "total": model_load_ms},
                "model_cached": model_cached,
            }

        inference_started = time.perf_counter()
        try:
            audio = {
                "array": np.asarray(samples, dtype=np.float32),
                "sampling_rate": int(sample_rate),
            }
            inference_started = time.perf_counter()
            with self._inference_lock:
                result = self._pipeline(
                    audio,
                    generate_kwargs={"task": "transcribe"}
                )
            inference_ms = round((time.perf_counter() - inference_started) * 1000, 2)
            text = ""
            if isinstance(result, dict):
                text = str(result.get("text") or "").strip()
            elif isinstance(result, str):
                text = result.strip()

            if not text:
                return {
                    "text": "",
                    "status": "empty",
                    "model_id": self.model_id,
                    "timings_ms": {"model_load": model_load_ms, "inference": inference_ms,
                                   "total": round(model_load_ms + inference_ms, 2)},
                    "model_cached": model_cached,
                }

            return {
                "text": text,
                "status": "ok",
                "model_id": self.model_id,
                "timings_ms": {"model_load": model_load_ms, "inference": inference_ms,
                               "total": round(model_load_ms + inference_ms, 2)},
                "model_cached": model_cached,
            }
        except Exception as exc:
            logger.error("Whisper inference failed (model=%s, error=%s).", self.model_id, type(exc).__name__)
            return {
                "text": "",
                "status": "failed",
                "model_id": self.model_id,
                "error": "transcription_failed",
                "timings_ms": {"model_load": model_load_ms, "inference": round(
                    (time.perf_counter() - inference_started) * 1000, 2
                ), "model_cached": model_cached},
            }
