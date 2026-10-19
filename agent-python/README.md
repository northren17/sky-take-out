# Python Voice Agent

Python provides the voice-agent backend only. The Vue voice-assistant UI belongs
to the customer web application in `..\user-web`; this project does not own
or serve a browser UI.

## Responsibilities

- FastAPI HTTP health endpoint and authenticated WebSocket voice protocol.
- Session-bound ASR, Agent/tool orchestration, RAG, and TTS.
- HMAC-authenticated calls to the Java service for authorized read-only business
  queries.
- No direct access to the Java application's business database.

The customer browser connects to the same-origin `/ws/voice` endpoint. In
development, the customer web Vite proxy forwards this path to
`http://localhost:8000`; in deployment, Nginx forwards it to the internal
`voice-agent` service.

## Configuration

Create a local `.env` from `.env.example` and provide the required model and
service credentials. Do not commit `.env` or put credentials in browser code,
WebSocket URLs, prompts, or logs. `/api/health` reports process health only; it
does not verify model credentials or Java business API availability.

## Run the backend

From this directory, create the Python 3.11 environment, install the pinned
dependencies, and copy the local configuration template:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\requirements.txt
Copy-Item .\.env.example .\.env
python .\start_backend.py
```

`python .\start-all.py` remains as a backward-compatible alias for existing
IDE run configurations; it starts the Python backend only. The Vue UI must be
started separately from `user-web`.

Run the customer UI separately from the workspace `user-web` directory:

```powershell
pnpm dev
```

## Tests

From this directory:

```powershell
python -B -m unittest discover -s tests -t . -v
python -m compileall -q agent tests
```

The backend tests do not replace real browser microphone, external ASR/LLM/TTS,
or production HTTPS/WSS validation.
