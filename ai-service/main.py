import json
import os
import secrets

from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from pydantic import BaseModel
from typing import Any

from services.nlp_service import NLPService
from services.adaptive_engine import AdaptiveSocialCueEngine
from services.audio_utils import AudioValidationError, load_mono_audio
from services.transcription_service import TranscriptionService
from services.voice_service import VoiceService
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


app = FastAPI(
    title="AdaptiveSense AI Service",
    version="1.1.0"
)

INTERNAL_KEY = os.getenv("AI_SERVICE_INTERNAL_KEY", "")

nlp_service = NLPService()
adaptive_engine = AdaptiveSocialCueEngine()
transcription_service = TranscriptionService()
voice_service = VoiceService()


class AnalyzeRequest(BaseModel):

    text: str

    previous_conversation: Any = ""

    previous_emotion: Any = {}


def verify_internal_key(
        x_internal_key: str | None) -> None:

    if not INTERNAL_KEY:
        return

    if not x_internal_key or not secrets.compare_digest(x_internal_key, INTERNAL_KEY):

        raise HTTPException(
            status_code=401,
            detail="Unauthorized"
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
        audio: UploadFile = File(...),
        previous_conversation: str = Form("[]"),
        previous_emotion: str = Form("{}"),
        x_internal_key: str | None = Header(
            default=None,
            alias="X-Internal-Key"
        )):

    verify_internal_key(x_internal_key)

    audio_bytes = await audio.read()
    filename = audio.filename or "recording.webm"
    content_type = audio.content_type

    try:
        samples, sample_rate = load_mono_audio(
            audio_bytes,
            filename,
            content_type
        )
    except AudioValidationError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail=str(exc)
        ) from None

    transcription = transcription_service.transcribe(
        samples,
        sample_rate
    )
    voice_emotion = voice_service.analyze(
        samples,
        sample_rate
    )

    transcript_text = transcription.get("text") or ""
    if transcription.get("status") != "ok" or not transcript_text.strip():
        raise HTTPException(
            status_code=422,
            detail="Speech could not be transcribed from the audio."
        )

    previous_conversation_data = _parse_json_field(
        previous_conversation,
        []
    )
    previous_emotion_data = _parse_json_field(
        previous_emotion,
        {}
    )

    nlp_result, social_cues = _run_analysis(
        transcript_text,
        previous_conversation_data,
        previous_emotion_data,
        voice_emotion
    )

    return {
        "text": transcript_text,
        "transcription": transcription,
        "nlp": nlp_result,
        "voice_emotion": voice_emotion,
        "social_cues": social_cues,
        "input_mode": "voice"
    }


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request,
    exc: RequestValidationError
):
    print("========== VALIDATION ERROR ==========")
    print("URL:", request.url.path)
    print("ERRORS:", exc.errors())
    print("BODY: [omitted]")
    print("======================================")

    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()}
    )
