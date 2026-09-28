import os
import secrets

from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel
from typing import Any

from services.nlp_service import NLPService
from services.adaptive_engine import AdaptiveSocialCueEngine
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


app = FastAPI(
    title="AdaptiveSense AI Service",
    version="1.0.0"
)

INTERNAL_KEY = os.getenv("AI_SERVICE_INTERNAL_KEY", "")

nlp_service = NLPService()
adaptive_engine = AdaptiveSocialCueEngine()


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

    nlp_result = nlp_service.analyze(
        request.text
    )

    social_cues = adaptive_engine.analyze(
        request.text,
        nlp_result,
        request.previous_conversation,
        request.previous_emotion
    )

    return {
        "text": request.text,
        "nlp": nlp_result,
        "social_cues": social_cues
    }

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request,
    exc: RequestValidationError
):
    print("========== VALIDATION ERROR ==========")
    print("URL:", request.url)
    print("ERRORS:", exc.errors())

    body = await request.body()
    print("BODY:", body.decode("utf-8", errors="replace"))
    print("======================================")

    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()}
    )