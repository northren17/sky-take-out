# Python Voice Agent

## Purpose and boundaries

This project is the Python service side of the voice assistant. It handles the
WebSocket voice protocol, ASR, Agent orchestration, RAG, TTS, and calls to
authorized Java read-only business APIs. It does not own a browser UI and must
not connect directly to the Java business database.

The Vue user interface, login state, ticket request, microphone controls, and
audio playback belong to the separate `user-web` application. The browser
connects to the Agent through the user web development proxy or production
Nginx; the Agent service is not exposed as a public website.

## Runtime flow

```text
user-web browser
  -> authenticated WebSocket
  -> FastAPI / VoiceSession
  -> ASR -> session-authorized Agent/tools/RAG -> TTS
  -> WebSocket events and audio frames

Agent tools
  -> HMAC-authenticated internal read-only API
  -> Java validates session capability and resolves the user
  -> Java order/coupon services
```

The Agent and model must not choose the authenticated user identity. Only the
validated Java session capability determines which user's read-only data can be
queried.

## Source layout

```text
agent-python/
├── agent/
│   ├── main.py              # FastAPI and WebSocket entry point
│   ├── session.py           # Per-connection voice lifecycle
│   ├── events.py            # Voice protocol event definitions
│   ├── internal_auth.py     # Java service authentication client
│   ├── providers/           # ASR and TTS adapters
│   ├── tools/               # Session-bound business and knowledge tools
│   └── rag/                 # Retrieval and knowledge-base logic
├── data/                    # Reviewed policy source data
├── tests/                   # Backend unit and integration tests
├── requirements.txt
├── Dockerfile
└── .env.example
```

Generated files, local environments, logs, model indexes, and secrets are not
source files and must not be committed. Keep Python modules in `snake_case.py`,
classes in `PascalCase`, and constants in `UPPER_SNAKE_CASE`.

## Local development

Create the Python 3.11 environment from `requirements.txt`, copy `.env.example`
to `.env`, fill credentials locally, then run from this directory:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\requirements.txt
Copy-Item .\.env.example .\.env
python .\start_backend.py
```

Existing IDE configurations may use `python .\start-all.py`; this compatibility
entry point starts the Python backend only. Run the customer UI separately from
`user-web` with `pnpm dev`. Its Vite
configuration proxies `/ws/voice` to the local Agent.

## Validation

```powershell
python -B -m unittest discover -s tests -t . -v
python -m compileall -q agent tests
```

Passing local tests does not establish that external model credentials,
real-browser audio, or production HTTPS/WSS are working.
