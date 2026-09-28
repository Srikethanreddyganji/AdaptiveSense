# AdaptiveSense

Empathetic mental wellness chat with local NLP signals (Python), Spring Boot API, and Gemini-generated responses.

## Architecture

- **frontend/** — React (Vite)
- **backend/** — Spring Boot, JWT auth, PostgreSQL
- **ai-service/** — FastAPI, RoBERTa sentiment/emotion + social cue engine

## Local development

### 1. Environment

Copy `.env.example` and set at least:

- `JWT_SECRET` (32+ characters)
- `GEMINI_API_KEY`
- PostgreSQL `DATABASE_*`

For the frontend, copy `frontend/.env.example` to `frontend/.env`.

### 2. AI service

```bash
cd ai-service
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 3. Backend

Export the same variables (or use your IDE env), then:

```bash
cd backend
./mvnw spring-boot:run
```

### 4. Frontend

```bash
cd frontend
npm install
npm run dev
```

## Render deployment notes

| Service | Key variables |
|--------|----------------|
| Backend | `JWT_SECRET`, `GEMINI_API_KEY`, `DATABASE_*`, `AI_SERVICE_URL` (public URL of Python service), `CORS_ALLOWED_ORIGINS` (comma-separated frontend URLs) |
| AI service | Optional `AI_SERVICE_INTERNAL_KEY` (same value on backend as `AI_SERVICE_INTERNAL_KEY`) |
| Frontend | `VITE_API_URL=https://your-backend.onrender.com/api` at **build** time |

Example CORS value:

```text
http://localhost:5173,https://your-frontend.onrender.com
```

## Security model

- Clients send `Authorization: Bearer <JWT>` on protected routes.
- User id is taken from the JWT, not from request bodies or paths.
- Optional shared secret between backend and Python via `X-Internal-Key`.

## Tests

```bash
cd backend && ./mvnw test
cd ai-service && python -m pytest tests/
```

## Disclaimer

AdaptiveSense is for emotional support and reflection, not diagnosis or emergency care. Users in crisis should contact local emergency services or a crisis helpline.
