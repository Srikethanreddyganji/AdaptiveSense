# AdaptiveSense

AdaptiveSense is a mental wellness reflection app with a React frontend, a JWT secured Spring Boot API, a Python analysis service, PostgreSQL persistence, and Gemini generated replies. It is not a diagnostic or emergency service.

## Architecture

```text
React/Vite ──JWT──> Spring Boot API ──> Neon PostgreSQL
                         │
                         ├── shared secret ──> FastAPI AI service
                         │                       ├── speech transcription
                         │                       ├── acoustic feature analysis
                         │                       └── NLP/social cue analysis
                         └── Gemini generateContent API
```

Voice requests upload the recorded `MediaRecorder` audio as multipart data. The AI service decodes that audio, transcribes it, and separately computes acoustic features and an uncertain voice emotion estimate. Browser speech recognition is sent only as a transcription fallback; it is never used as acoustic analysis. Acoustic and emotion signals are supportive and non-diagnostic. Facial analysis is out of scope.

## Repository layout

- `frontend/` — React 19, Vite, authentication screens, dashboard, text chat, voice conversation, history and emotion views.
- `backend/` — Spring Boot 4.1.1, Java 21, JWT security, registration/login, chat/history APIs, Gemini client, Flyway migration, persistence entities and repositories.
- `ai-service/` — FastAPI, audio decoding/validation, Whisper transcription, acoustic feature extraction, NLP and adaptive social cue analysis.
- `render.yaml` — Render service definitions. Set the manual URLs and credentials marked `sync: false` in the Render dashboard after creating services.

## Requirements

- Java 21 and the included Maven wrapper.
- Node.js compatible with Vite 8 (Node 20.19+ or 22.12+).
- Python 3.10+ (the included AI-service environment uses Python 3.12).
- FFmpeg for decoding browser WebM recordings when the Python audio decoders cannot read the container.
- A PostgreSQL database for persistent local or production use. H2 is used only for backend tests.
- A Gemini API key for generated responses. No key is needed to run mocked tests.

## Environment variables

Copy `.env.example` for the backend values and `frontend/.env.example` for the frontend. Set these values in the environment of the corresponding service; do not commit secrets.

| Variable | Service | Purpose |
|---|---|---|
| `DATABASE_URL` | Backend | JDBC URL, for example `jdbc:postgresql://localhost:5432/adaptivesense`; for Neon use its JDBC hostname/database and SSL parameters. |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Backend | Database credentials. |
| `JWT_SECRET` | Backend | Random secret of at least 32 characters. |
| `JWT_EXPIRATION_MS` | Backend | Token lifetime in milliseconds (default 86400000). |
| `GEMINI_API_KEY` | Backend | Gemini API key, sent in the `x-goog-api-key` header. |
| `GEMINI_MODEL` | Backend | Gemini model ID (default `gemini-3.8-flash`). |
| `AI_SERVICE_URL` | Backend | Base URL of FastAPI (default `http://localhost:8000`). |
| `AI_SERVICE_INTERNAL_KEY` | Backend and AI service | Shared secret for backend-to-AI requests; required when `ENVIRONMENT=production`. |
| `CORS_ALLOWED_ORIGINS` | Backend | Comma-separated exact browser origins, such as `http://localhost:5173,https://app.example.com`. Do not use `*` with credentials. |
| `ENVIRONMENT` | Backend and AI service | Set to `production` in deployment; enforces internal service key configuration. |
| `JPA_DDL_AUTO` | Backend | Hibernate schema mode (default `update` locally; Render sets `validate` after Flyway migration). |
| `JPA_SHOW_SQL` | Backend | SQL logging toggle (default `false`; keep false in production). |
| `AUDIO_MAX_SIZE_BYTES` | Backend | Upload size limit (default 10 MiB). |
| `AUDIO_MAX_REQUEST_SIZE_BYTES` | Backend | Multipart request limit including form overhead (default 11 MiB). |
| `AUDIO_MAX_DURATION_SECONDS` | Backend | Intended maximum audio duration (default 60 seconds). |
| `VITE_API_URL` | Frontend build | Backend API base URL including `/api`, e.g. `https://api.example.com/api`. |
| `MAX_AUDIO_BYTES`, `MAX_AUDIO_SECONDS`, `MIN_AUDIO_SECONDS` | AI service | Audio validation limits (defaults 10 MiB, 60 seconds, 0.3 seconds). |
| `WHISPER_MODEL` | AI service | Hugging Face ASR model (default `openai/whisper-tiny`). |
| `WHISPER_LOCAL_FILES_ONLY` | AI service | Load Whisper assets from cache without Hub lookups (default `false`; requires the configured model to be cached). |
| `VOICE_EMOTION_ENABLED` | AI service | Enables the voice emotion model (default `true`; acoustic features remain computed even if the model is unavailable). |
| `VOICE_EMOTION_MODEL` | AI service | Hugging Face voice emotion model (default `superb/wav2vec2-base-superb-er`). |
| `VOICE_EMOTION_LOCAL_FILES_ONLY` | AI service | Load wav2vec2 assets from cache without Hub lookups (default `false`; requires the configured model to be cached). |

The backend `AUDIO_MAX_DURATION_SECONDS` is currently a deployment setting only; authoritative decoded duration validation happens in the AI service through `MAX_AUDIO_SECONDS`.

## Local installation and run

Open three terminals from the repository root.

### 1. PostgreSQL

Create a PostgreSQL database named `adaptivesense` (or use an existing database), then set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. The URL must be a JDBC URL. Flyway applies versioned SQL migrations; local Hibernate `update` remains enabled to ease development.

### 2. Python AI service

```powershell
cd ai-service
python -m venv venv
.\venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
python -m uvicorn main:app --host 127.0.0.1 --port 8000
```

On Windows, install FFmpeg and ensure `ffmpeg.exe` is on `PATH` to support WebM decoding. If it is installed outside `PATH` (for example, a user-scoped Winget install), set `FFMPEG_PATH` to its full executable path; the service also discovers the standard Gyan FFmpeg Winget location automatically. In local development, the first use may download a model from Hugging Face. Set the corresponding `*_LOCAL_FILES_ONLY=true` flag after that model is cached to avoid Hub lookup delays. The AI-service Docker image pre-caches the default wav2vec2 and Whisper assets and enables local-only loading. Set `AI_SERVICE_INTERNAL_KEY` and `ENVIRONMENT=production` only when running the AI service in production; configure the same key on the backend.

### 3. Spring Boot backend

Set the backend environment variables in the shell or IDE, then:

```powershell
cd backend
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/adaptivesense"
$env:DATABASE_USERNAME = "postgres"
$env:DATABASE_PASSWORD = "<your-local-postgres-password>"
$env:JWT_SECRET = "<random-secret-at-least-32-characters>"
$env:GEMINI_API_KEY = "<your-gemini-api-key>"
$env:AI_SERVICE_URL = "http://localhost:8000"
$env:CORS_ALLOWED_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174"
$env:ENVIRONMENT = "development"
$env:PORT = "8081"
.\mvnw.cmd spring-boot:run
```

The backend listens on `http://localhost:8081`; its unauthenticated health endpoint is `GET /api/auth/health`. Vite proxies `/api` to this port by default; override `VITE_BACKEND_URL` only when using a different backend port.

### 4. React frontend

```powershell
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

Vite runs at `http://localhost:5174`. `VITE_API_URL` is embedded at build time and defaults to the same-origin `/api` proxy in development. Registration and login return JWTs used by the protected API.

## Testing and build

Run the backend tests (Gemini calls are not made by the unit/integration suite):

```powershell
cd backend
.\mvnw.cmd test
```

Run the AI-service tests (install development dependencies first with `python -m pip install -r requirements-dev.txt`):

```powershell
cd ai-service
python -m pytest tests/ -q
```

Build the production frontend:

```powershell
cd frontend
npm run build
```

The voice pipeline tests mock speech transcription where needed; they do not verify a live Whisper model download, Gemini credentials, or production network access.

## Production deployment (Render + Neon)

1. Create a Neon PostgreSQL project and database. Copy its connection information. Set the backend `DATABASE_URL` to a JDBC-form Neon URL and populate `DATABASE_USERNAME` and `DATABASE_PASSWORD`. Keep the SSL connection requirement from Neon.
2. Create a Render Blueprint from this repository and apply `render.yaml`. It defines the Python service, Spring Boot API, and static frontend.
3. For the AI and backend services, confirm `ENVIRONMENT=production` and that the generated `AI_SERVICE_INTERNAL_KEY` is identical on both services. The Blueprint links the backend to the AI service's private address and shared key.
4. In the backend service, set `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `GEMINI_API_KEY`. Set `GEMINI_MODEL` to an available Gemini model ID. `JWT_SECRET` is generated by Render.
5. Set backend `CORS_ALLOWED_ORIGINS` to the exact deployed frontend origin (scheme plus hostname, without a path). Do not leave it empty or wildcarded.
6. Set the frontend build variable `VITE_API_URL` to the deployed backend URL plus `/api`, for example `https://adaptivesense-backend.onrender.com/api`. Trigger a frontend rebuild after changing it.
7. Wait for all services to become healthy. Check `GET https://<backend-host>/api/auth/health` and `GET https://<ai-host>/health`. Then register a test account, send text, and record a short voice sample to verify auth, database persistence, AI service connectivity, acoustic result response, and browser speech playback.
8. Confirm Neon backups/retention, Render service plans, usage limits, and Gemini quota/billing in their respective consoles. Those account settings cannot be verified from this repository.

The initial Flyway migration creates the schema on a new database; the follow-up migration adds ownership foreign keys and indexes for both new and existing schemas. For an existing database previously managed by Hibernate, Flyway baselines nonempty schemas at version 1 and applies the follow-up migration. Take a backup first and verify there are no orphaned rows before deployment. Free services may sleep and cold-start; the local development flow is better for validating long-running model initialization.

## Security and limitations

- Protected APIs derive user identity from the JWT. The browser never receives Gemini or internal-service credentials.
- The backend authenticates AI-service requests with `X-Internal-Key`; production startup configuration must provide the same key on both services.
- Audio is validated by size, format decoding, duration, and usable signal checks. Uploaded recordings are processed in memory and are not stored as audio files.
- Logs must not contain prompt content, audio bytes, Gemini response bodies, JWTs, or API keys.
- Voice emotion and acoustic values are uncertain support signals, not diagnosis. AdaptiveSense does not diagnose depression or any other condition and is not emergency care.
