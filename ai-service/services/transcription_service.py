import os
import threading

import numpy as np
import torch


class TranscriptionService:

    def __init__(self):
        self._lock = threading.Lock()
        self._pipeline = None
        self._load_attempted = False
        self._load_error = None
        self.model_id = os.getenv(
            "WHISPER_MODEL",
            "openai/whisper-tiny"
        )

    def _load_pipeline(self):
        if self._pipeline is not None or self._load_attempted:
            return

        with self._lock:
            if self._pipeline is not None or self._load_attempted:
                return

            self._load_attempted = True

            try:
                from transformers import pipeline

                torch.set_num_threads(1)
                self._pipeline = pipeline(
                    "automatic-speech-recognition",
                    model=self.model_id,
                    device=-1,
                    model_kwargs={"low_cpu_mem_usage": True},
                )
                self._load_error = None
            except Exception as exc:
                self._pipeline = None
                self._load_error = "transcription_model_unavailable"
                print(
                    f"Warning: Whisper model could not be loaded ({type(exc).__name__})."
                )

    def transcribe(self, samples: np.ndarray, sample_rate: int) -> dict:
        if samples is None or samples.size == 0:
            return {
                "text": "",
                "status": "empty",
                "model_id": self.model_id,
            }

        self._load_pipeline()

        if self._pipeline is None:
            return {
                "text": "",
                "status": "failed",
                "model_id": self.model_id,
                "error": self._load_error or "transcription_unavailable",
            }

        try:
            audio = {
                "array": np.asarray(samples, dtype=np.float32),
                "sampling_rate": int(sample_rate),
            }
            result = self._pipeline(
                audio,
                generate_kwargs={"task": "transcribe"}
            )
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
                }

            return {
                "text": text,
                "status": "ok",
                "model_id": self.model_id,
            }
        except Exception as exc:
            print(
                f"Warning: transcription failed ({type(exc).__name__})."
            )
            return {
                "text": "",
                "status": "failed",
                "model_id": self.model_id,
                "error": "transcription_failed",
            }
