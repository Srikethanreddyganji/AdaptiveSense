import asyncio
import json
import os
import secrets
import time
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from pydantic import BaseModel
from typing import Any

from services.nlp_service import NLPService
from services.adaptive_engine import AdaptiveSocialCueEngine
from services.audio_utils import AudioValidationError, MAX_AUDIO_BYTES, load_mono_audio
from services.transcription_service import TranscriptionService
from services.voice_service import VoiceService
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
import logging
import uuid


app = FastAPI(
    title="AdaptiveSense AI Service",
    version="1.1.0"
)
logger = logging.getLogger(__name__)


def _enable_safe_stage_logging(logger_name: str) -> None:
    """Emit allowlisted stage logs even when Uvicorn keeps the root logger at WARNING."""
    stage_logger = logging.getLogger(logger_name)
    stage_logger.setLevel(logging.INFO)
    stage_logger.propagate = False
    if not stage_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
        stage_logger.addHandler(handler)


for _stage_logger_name in (
    __name__,
    "services.voice_service",
    "services.transcription_service",
):
    _enable_safe_stage_logging(_stage_logger_name)


@app.middleware("http")
async def log_voice_request_lifecycle(request: Request, call_next):
    """Log a safe correlated summary even when audio validation/model stages fail."""
    if request.url.path != "/analyze-voice":
        return await call_next(request)

    candidate = request.headers.get("X-Request-ID", "")
    request_id = (
        candidate if candidate.isascii()
        and candidate.replace("-", "").replace("_", "").isalnum()
        and len(candidate) <= 80
        else str(uuid.uuid4())
    )
    request.state.voice_request_id = request_id
    started = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception as exc:
        logger.error(
            "voice_request_summary request_id=%s status=500 total_processing_ms=%.2f "
            "content_length_bytes=%s failure=%s",
            request_id, (time.perf_counter() - started) * 1000,
            request.headers.get("content-length", "unknown"), type(exc).__name__,
        )
        raise

    logger.info(
        "voice_request_summary request_id=%s status=%d total_processing_ms=%.2f "
        "content_length_bytes=%s",
        request_id, response.status_code, (time.perf_counter() - started) * 1000,
        request.headers.get("content-length", "unknown"),
    )
    return response

ENVIRONMENT = os.getenv("ENVIRONMENT", os.getenv("APP_ENV", "development")).strip().lower()
INTERNAL_KEY = os.getenv("AI_SERVICE_INTERNAL_KEY", "").strip()

nlp_service = NLPService()
adaptive_engine = AdaptiveSocialCueEngine()
transcription_service = TranscriptionService()
voice_service = VoiceService()
audio_analysis_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="voice-analysis")


def _timed_call(function, *args):
    started = time.perf_counter()
    result = function(*args)
    return result, round((time.perf_counter() - started) * 1000, 2)


def _service_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={"code": code, "message": message},
    )


class AnalyzeRequest(BaseModel):

    text: str

    previous_conversation: Any = ""

    previous_emotion: Any = {}


def verify_internal_key(
        x_internal_key: str | None) -> None:

    is_production = ENVIRONMENT in {"production", "prod"}
    if is_production and not INTERNAL_KEY:
        raise HTTPException(
            status_code=500,
            detail={
                "code": "INTERNAL_AUTHENTICATION_FAILURE",
                "message": "AI service authentication is not configured.",
            }
        )

    if INTERNAL_KEY:
        if not x_internal_key or not secrets.compare_digest(x_internal_key, INTERNAL_KEY):
            raise HTTPException(
                status_code=401,
                detail={
                    "code": "INTERNAL_AUTHENTICATION_FAILURE",
                    "message": "AI service authentication failed.",
                }
            )


def _parse_json_field(raw_value: str | None, fallback):
    if raw_value is None or raw_value == "":
        return fallback

    try:
        return json.loads(raw_value)
    except json.JSONDecodeError:
        return fallback


def _run_analysis(
        text: str,
        previous_conversation,
        previous_emotion,
        voice_emotion=None):

    nlp_result = nlp_service.analyze(text)

    social_cues = adaptive_engine.analyze(
        text,
        nlp_result,
        previous_conversation,
        previous_emotion,
        voice_emotion
    )

    return nlp_result, social_cues


@app.get("/")
def root():

    return {
        "service": "AdaptiveSense AI Service",
        "status": "running"
    }


@app.get("/health")
def health():

    return {"status": "ok"}


@app.post("/analyze")
def analyze(
        request: AnalyzeRequest,
        x_internal_key: str | None = Header(
            default=None,
            alias="X-Internal-Key"
        )):

    verify_internal_key(x_internal_key)

    nlp_result, social_cues = _run_analysis(
        request.text,
        request.previous_conversation,
        request.previous_emotion,
        None
    )

    return {
        "text": request.text,
        "nlp": nlp_result,
        "social_cues": social_cues,
        "input_mode": "text"
    }


@app.post("/analyze-voice")
async def analyze_voice(
        request: Request,
        audio: UploadFile = File(...),
        transcript: str = Form(""),
        previous_conversation: str = Form("[]"),
        previous_emotion: str = Form("{}"),
        x_internal_key: str | None = Header(
            default=None,
            alias="X-Internal-Key"
        )):

    verify_internal_key(x_internal_key)

    request_started = time.perf_counter()
    request_id = request.state.voice_request_id
    read_started = time.perf_counter()
    audio_bytes = await audio.read(MAX_AUDIO_BYTES + 1)
    upload_read_ms = round((time.perf_counter() - read_started) * 1000, 2)
    if len(audio_bytes) > MAX_AUDIO_BYTES:
        raise _service_error(
            413,
            "RESOURCE_EXHAUSTION",
            "The audio recording exceeds the service size limit.",
        )
    filename = audio.filename or "recording.webm"
    content_type = audio.content_type
    logger.info(
        "Voice upload accepted: bytes=%d, extension=%s, content_type=%s.",
        len(audio_bytes), os.path.splitext(filename)[1].lower(), content_type or "unknown",
    )

    try:
        decode_started = time.perf_counter()
        audio_stage_timings = {}
        samples, sample_rate = await asyncio.get_running_loop().run_in_executor(
            None, lambda: load_mono_audio(audio_bytes, filename, content_type, timings=audio_stage_timings)
        )
        decode_ms = audio_stage_timings.get("decode", round((time.perf_counter() - decode_started) * 1000, 2))
    except AudioValidationError as exc:
        code = "RESOURCE_EXHAUSTION" if exc.status_code == 413 else "AUDIO_DECODING_FAILURE"
        message = (
            "The audio recording exceeds the service size or duration limit."
            if exc.status_code == 413
            else "The audio recording could not be decoded. Check that it is a supported audio format."
        )
        logger.warning("Audio decode rejected (%s, status=%d).", code, exc.status_code)
        raise _service_error(exc.status_code, code, message) from None
    except MemoryError:
        logger.error("Audio decoding exhausted available memory.")
        raise _service_error(
            503, "RESOURCE_EXHAUSTION", "The audio service is temporarily out of resources."
        ) from None
    except Exception as exc:
        logger.error("Audio decoding failed (%s).", type(exc).__name__)
        raise _service_error(
            422, "AUDIO_DECODING_FAILURE", "The audio recording could not be decoded."
        ) from None

    client_text = (transcript or "").strip()
    loop = asyncio.get_running_loop()
    parallel_started = time.perf_counter()
    if client_text:
        # Browser speech recognition already supplied a transcript. Reuse it and
        # still analyze the uploaded audio for acoustic/emotion features.
        transcription_future = loop.create_future()
        transcription_future.set_result(({
            "text": client_text,
            "status": "client",
            "model_id": "browser_speech_recognition",
        }, 0.0))
    else:
        transcription_future = loop.run_in_executor(
            audio_analysis_executor,
            _timed_call,
            transcription_service.transcribe,
            samples,
            sample_rate,
        )
    voice_future = loop.run_in_executor(
        audio_analysis_executor,
        _timed_call,
        voice_service.analyze,
        samples,
        sample_rate,
    )
    try:
        (transcription, transcription_ms), (voice_emotion, voice_analysis_ms) = await asyncio.wait_for(
            asyncio.gather(transcription_future, voice_future),
            timeout=float(os.getenv("VOICE_ANALYSIS_TIMEOUT_SECONDS", "180")),
        )
    except asyncio.TimeoutError:
        logger.warning("Voice analysis request timed out.")
        raise _service_error(
            504, "REQUEST_TIMEOUT", "Voice analysis took too long. Please try a shorter recording."
        ) from None
    except MemoryError:
        logger.error("Voice analysis exhausted available memory.")
        raise _service_error(
            503, "RESOURCE_EXHAUSTION", "The audio service is temporarily out of resources."
        ) from None
    except Exception as exc:
        logger.error("Voice analysis failed (%s).", type(exc).__name__)
        raise _service_error(
            502, "VOICE_ANALYSIS_FAILURE", "Acoustic voice analysis could not be completed."
        ) from None
    parallel_analysis_wall_ms = round((time.perf_counter() - parallel_started) * 1000, 2)

    transcript_text = (transcription.get("text") or "").strip()
    if not transcript_text:
        if transcription.get("error") == "transcription_model_unavailable":
            logger.error("Whisper model unavailable (model=%s).", transcription.get("model_id"))
            raise _service_error(
                503, "MODEL_LOADING_FAILURE", "The transcription model is not available right now."
            )
        logger.warning("Audio transcription returned no text (status=%s).", transcription.get("status"))
        raise _service_error(
            422, "TRANSCRIPTION_FAILURE", "Speech could not be transcribed from the audio."
        )

    previous_conversation_data = _parse_json_field(
        previous_conversation,
        []
    )
    previous_emotion_data = _parse_json_field(
        previous_emotion,
        {}
    )

    nlp_started = time.perf_counter()
    nlp_result, social_cues = _run_analysis(
        transcript_text,
        previous_conversation_data,
        previous_emotion_data,
        voice_emotion
    )
    nlp_ms = round((time.perf_counter() - nlp_started) * 1000, 2)

    timings_ms = {
        "upload_read": upload_read_ms,
        "decode": decode_ms,
        "resample": audio_stage_timings.get("resample", 0.0),
        "transcription": transcription_ms,
        "transcription_stages": transcription.get("timings_ms", {}),
        "voice_analysis": voice_analysis_ms,
        "voice_analysis_stages": voice_emotion.get("timings_ms", {}),
        "parallel_analysis_wall": parallel_analysis_wall_ms,
        "nlp_and_adaptive_cues": nlp_ms,
        "total": round((time.perf_counter() - request_started) * 1000, 2),
    }

    acoustic_features = voice_emotion.get("acoustic_features") or {}
    emotion_available = voice_emotion.get("status") == "ok"
    logger.info(
        "voice_request_timing request_id=%s audio_duration_seconds=%.3f audio_size_bytes=%d "
        "transcription_source=%s upload_read_ms=%.2f transcription_ms=%.2f "
        "decode_ms=%.2f resample_ms=%.2f "
        "transcription_model_load_ms=%.2f transcription_inference_ms=%.2f "
        "preprocessing_ms=%.2f acoustic_analysis_ms=%.2f model_load_ms=%.2f "
        "inference_ms=%.2f voice_analysis_total_ms=%.2f parallel_analysis_wall_ms=%.2f "
        "nlp_ms=%.2f total_processing_ms=%.2f "
        "model_cached=%s transcription_status=%s emotion_status=%s",
        request_id, samples.size / float(sample_rate), len(audio_bytes),
        "browser" if transcription.get("status") == "client" else "whisper",
        upload_read_ms, transcription_ms, decode_ms, timings_ms["resample"],
        transcription.get("timings_ms", {}).get("model_load", 0.0),
        transcription.get("timings_ms", {}).get("inference", 0.0),
        voice_emotion.get("timings_ms", {}).get("preprocessing", 0.0),
        voice_emotion.get("timings_ms", {}).get("acoustic_features", 0.0),
        voice_emotion.get("timings_ms", {}).get("model_load", 0.0),
        voice_emotion.get("timings_ms", {}).get("inference", 0.0),
        voice_analysis_ms, parallel_analysis_wall_ms, nlp_ms, timings_ms["total"],
        voice_emotion.get("model_cached", "unknown"),
        transcription.get("status", "unknown"), voice_emotion.get("status", "unknown"),
    )

    return {
        "text": transcript_text,
        "transcription": transcription,
        "nlp": nlp_result,
        "voice_emotion": voice_emotion,
        "acoustic_features": acoustic_features,
        "analysis_status": "complete" if emotion_available else "partial",
        "analysis_warnings": [] if emotion_available else ["VOICE_EMOTION_UNAVAILABLE"],
        "social_cues": social_cues,
        "input_mode": "voice",
        "timings_ms": timings_ms,
    }


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request,
    exc: RequestValidationError
):
    logger.warning(
        "Request validation failed for %s with %d issue(s); submitted values omitted.",
        request.url.path,
        len(exc.errors()),
    )
    safe_errors = [
        {key: error[key] for key in ("loc", "msg", "type") if key in error}
        for error in exc.errors()
    ]

    return JSONResponse(
        status_code=422,
        content={"detail": safe_errors, "code": "INVALID_REQUEST"}
    )
