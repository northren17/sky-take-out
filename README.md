# Sky Take Out

This repository contains the Sky Take Out Java backend, administrator web app,
customer web app, and Python voice-agent backend.

## Project layout

| Directory | Responsibility | Local development |
|---|---|---|
| `backed/` | Java Spring Boot business APIs | See [`backed/LOCAL_CONFIGURATION.md`](backed/LOCAL_CONFIGURATION.md) |
| `admin-web/` | Vue administrator application | Use scripts in `admin-web/package.json` |
| `user-web/` | Vue customer application and voice-assistant UI | `cd user-web` then `pnpm dev` |
| `agent-python/` | FastAPI voice-agent backend (no browser UI) | `cd agent-python`, configure local `.env`, then run `python start-all.py` |

The customer browser connects to the Python Agent through the user web
development proxy or the production reverse proxy. Java remains the authority
for authentication and business data; the Python Agent must not access the Java
business database directly.

See [`PROJECT_STRUCTURE.md`](PROJECT_STRUCTURE.md) for ownership boundaries and
naming conventions. Do not commit `.env` files, private credentials, virtual
environments, dependency directories, logs, or generated build output.
