# AdaptiveSense

AdaptiveSense is a mental wellness reflection app with a React frontend, a JWT-secured Spring Boot API, a Python analysis service, PostgreSQL persistence, and local Qwen responses through Ollama. It is not a diagnostic or emergency service.

## Architecture

```text
React/Vite ──JWT──> Spring Boot API ──> Neon PostgreSQL
                         │
                         ├── shared secret ──> FastAPI AI service
                         │                       ├── speech transcription
                         │                       ├── acoustic feature analysis
                         │                       └── NLP/social cue analysis
                         └── Ollama /api/chat (Qwen 2.5 3B, local)
```

Voice requests upload the recorded `MediaRecorder` audio as multipart data. The AI service decodes that audio, transcribes it, and separately computes acoustic features and an uncertain voice emotion estimate. Browser speech recognition is sent only as a transcription fallback; it is never used as acoustic analysis. Acoustic and emotion signals are supportive and non-diagnostic.

## Repository layout

- `frontend/` — React 19, Vite, authentication screens, dashboard, text chat, voice conversation, history and emotion views.
- `backend/` — Spring Boot 4.1.1, Java 21, JWT security, registration/login, chat/history APIs, Ollama client, Flyway migrations, persistence entities and repositories.
- `ai-service/` — FastAPI, audio decoding/validation, Whisper transcription, acoustic feature extraction, NLP and adaptive social cue analysis.
- `render.yaml` — Render service definitions. Set the manual URLs and credentials marked `sync: false` in the Render dashboard after creating services.

## Requirements

- Java 21 and the included Maven wrapper.
- Node.js compatible with Vite 8 (Node 20.19+ or 22.12+).
- Python 3.10+ (the included AI-service environment uses Python 3.12).
- FFmpeg for decoding browser WebM recordings when the Python audio decoders cannot read the container.
- A PostgreSQL database for persistent local or production use. H2 is used only for backend tests.
- Ollama installed on the backend host with `qwen2.5:3b` pulled locally. Conversational response generation runs through the local Ollama service.

## Environment variables

Copy `.env.example` for the backend values and `frontend/.env.example` for the frontend. Set these values in the environment of the corresponding service; do not commit secrets.

| Variable | Service | Purpose |
|---|---|---|
| `DATABASE_URL` | Backend | JDBC URL, for example `jdbc:postgresql://localhost:5432/adaptivesense`; for Neon use its JDBC hostname/database and SSL parameters. |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Backend | Database credentials. |
| `JWT_SECRET` | Backend | Random secret of at least 32 characters. |
| `JWT_EXPIRATION_MS` | Backend | Token lifetime in milliseconds (default 86400000). |
| `OLLAMA_BASE_URL` | Backend | Local Ollama address (default `http://127.0.0.1:11434`). |
| `OLLAMA_MODEL` | Backend | Local model tag (default `qwen2.5:3b`). |
| `OLLAMA_CONNECT_TIMEOUT`, `OLLAMA_READ_TIMEOUT` | Backend | Ollama connection/read timeouts (defaults `5s` and `180s`). |
| `OLLAMA_NUM_CTX`, `OLLAMA_NUM_PREDICT` | Backend | Bounded prompt context and generated token count (defaults `4096` and `220`). |
| `OLLAMA_NUM_THREAD` | Backend | Optional CPU thread override; `0` omits the option and lets Ollama choose (default `0`). |
| `OLLAMA_KEEP_ALIVE` | Backend | How long Ollama keeps the model resident after a request (default `30m`). |
| `OLLAMA_REQUEST_QUEUE_TIMEOUT` | Backend | Maximum wait for the single in-process generation permit (default `90s`). |
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
| `WHISPER_LOCAL_FILES_ONLY` | AI service | Load Whisper assets from cache without Hub lookups (default `true`; requires the configured model to be cached). |
| `VOICE_EMOTION_ENABLED` | AI service | Enables the voice emotion model (default `true`; acoustic features remain computed even if the model is unavailable). |
| `VOICE_EMOTION_MODEL` | AI service | Hugging Face voice emotion model (default `superb/wav2vec2-base-superb-er`). |
| `VOICE_EMOTION_LOCAL_FILES_ONLY` | AI service | Load wav2vec2 assets from cache without Hub lookups (default `true`; requires the configured model to be cached). |
| `AI_TORCH_THREADS` | AI service | Shared bound for intra-op threads across NLP, Whisper and wav2vec2 (default `2`; tune to the host's CPU/memory budget). |

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

Install and warm the local response model once before using chat:

```powershell
ollama pull qwen2.5:3b
Invoke-RestMethod http://127.0.0.1:11434/api/tags
```

The backend sends streamed requests and asks Ollama to retain Qwen for 30 minutes. Generation is serialized in the backend to avoid multiple concurrent model inferences on low-memory machines. For a dedicated Ollama host, set `OLLAMA_MAX_LOADED_MODELS=1` and `OLLAMA_NUM_PARALLEL=1` in the Ollama process environment before starting it; AdaptiveSense does not start or restart Ollama. Leave `OLLAMA_NUM_THREAD=0` unless benchmarking on the target machine: a matched local run here measured 6.98 tokens/sec with Ollama's default and 6.80 with four threads, while the separately supplied benchmark favored four threads.

### 3. Spring Boot backend

Set the backend environment variables in the shell or IDE, then:

```powershell
cd backend
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/adaptivesense"
$env:DATABASE_USERNAME = "postgres"
$env:DATABASE_PASSWORD = "<your-local-postgres-password>"
$env:JWT_SECRET = "<random-secret-at-least-32-characters>"
$env:OLLAMA_BASE_URL = "http://127.0.0.1:11434"
$env:OLLAMA_MODEL = "qwen2.5:3b"
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

Run the backend tests (Ollama HTTP calls are mocked by the provider tests):

```powershell
cd backend
.\mvnw.cmd test
```

Run the AI-service tests (install development dependencies first with `python -m pip install -r requirements-dev.txt`):

```powershell
cd ai-service
python -m pytest tests/ -q
```

Run the frontend checks:

```powershell
cd frontend
npm test
npm run lint
npm run build
```

Build the production frontend:

```powershell
cd frontend
npm run build
```

The voice pipeline tests mock speech transcription where needed; they do not verify a live Whisper model download, Ollama availability, or production network access.

## Production deployment (Render + Neon)

1. Create a Neon PostgreSQL project and database. Copy its connection information. Set the backend `DATABASE_URL` to a JDBC-form Neon URL and populate `DATABASE_USERNAME` and `DATABASE_PASSWORD`. Keep the SSL connection requirement from Neon.
2. Create a Render Blueprint from this repository and apply `render.yaml`. It defines the Python service, Spring Boot API, and static frontend.
3. For the AI and backend services, confirm `ENVIRONMENT=production` and that the generated `AI_SERVICE_INTERNAL_KEY` is identical on both services. The Blueprint links the backend to the AI service's private address and shared key.
4. The backend must be able to reach the Ollama host over a trusted private network. Set `OLLAMA_BASE_URL` and `OLLAMA_MODEL=qwen2.5:3b` on the backend. A backend deployed to Render cannot use Ollama at `127.0.0.1` unless Ollama is running in the same deployment environment; the user's local Ollama is not reachable from Render. `JWT_SECRET` is generated by Render.
5. Set backend `CORS_ALLOWED_ORIGINS` to the exact deployed frontend origin (scheme plus hostname, without a path). Do not leave it empty or wildcarded.
6. Set the frontend build variable `VITE_API_URL` to the deployed backend URL plus `/api`, for example `https://adaptivesense-backend.onrender.com/api`. Trigger a frontend rebuild after changing it.
7. Wait for all services to become healthy. Check `GET https://<backend-host>/api/auth/health` and `GET https://<ai-host>/health`. Then register a test account, send text, and record a short voice sample to verify auth, database persistence, AI service connectivity, acoustic result response, and browser speech playback.
8. Confirm Neon backups/retention and Render service plans in their respective consoles. Keep Ollama private; do not expose its unauthenticated API directly to the public internet. Those account settings cannot be verified from this repository.

The initial Flyway migration creates the schema on a new database; the follow-up migration adds ownership foreign keys and indexes for both new and existing schemas. For an existing database previously managed by Hibernate, Flyway baselines nonempty schemas at version 1 and applies the follow-up migration. Take a backup first and verify there are no orphaned rows before deployment. Free services may sleep and cold-start; the local development flow is better for validating long-running model initialization.

## Security and limitations

- Protected APIs derive user identity from the JWT. The browser never receives database, Ollama, or internal-service configuration.
- The backend authenticates AI-service requests with `X-Internal-Key`; production startup configuration must provide the same key on both services.
- Audio is validated by size, format decoding, duration, and usable signal checks. Uploaded recordings are processed in memory and are not stored as audio files.
- Logs must not contain prompt content, audio bytes, Ollama response bodies, JWTs, or credentials.
- Voice emotion and acoustic values are uncertain support signals, not diagnosis. AdaptiveSense does not diagnose depression or any other condition and is not emergency care.
