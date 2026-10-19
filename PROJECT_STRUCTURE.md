# Workspace Structure and Naming

## Active application roots

| Directory | Responsibility | Runtime |
|---|---|---|
| `admin-web/` | Vue administrator application | Node.js / Vite |
| `user-web/` | Vue customer ordering application and voice-assistant UI | Node.js / Vite |
| `agent-python/` | FastAPI voice-agent backend: WebSocket, ASR, Agent/tools, RAG, and TTS | Python |
| `backed/` | Existing Java business backend | Java / Maven |

Each application owns its dependency manifest, tests, build configuration, and
Docker context. Do not share `node_modules`, Python environments, build output,
or application source between roots. Keep generated dependencies, local secrets,
logs, and build artifacts out of source control.

The Python service does not contain or serve a browser UI. The browser voice
experience, microphone permission flow, ticket request, WebSocket client, and
audio playback live in `user-web/`. The browser reaches the Python service
through the user application's development proxy or the deployment reverse
proxy.

The Java root remains `backed/`; its directory and application code are outside
the scope of this frontend/Python structure cleanup.

## Naming conventions

- Workspace application directories: lowercase kebab-case with a role suffix,
  such as `admin-web`, `user-web`, and `agent-python`.
- Vue components: PascalCase `.vue` names, such as `VoiceAssistant.vue`.
- Vue composables: `use` prefix with camelCase, such as `useVoiceSession.ts`.
- TypeScript/JavaScript modules and API files: camelCase; route directories and
  URL paths: lowercase kebab-case where the existing router supports it.
- Python modules and packages: `snake_case.py`; classes: `PascalCase`;
  constants: `UPPER_SNAKE_CASE`.
- Preserve established Java package/class naming in the existing backend;
  do not bulk-rename mature sources as part of frontend/Python organization.

Apply naming changes when adding or intentionally refactoring files. Avoid
mechanical bulk renames of existing routes or imports without a separately
verified migration.

## Local development

- Customer UI: from `user-web/`, run `pnpm dev`.
- Admin UI: use the scripts in `admin-web/package.json`.
- Voice Agent: from `agent-python/`, run `python .\start_backend.py` after
  preparing its virtual environment and local `.env`.
- Start Java independently using the instructions under `backed/`.

Never put model keys, database passwords, service HMAC keys, or user tokens in
frontend environment variables, source files, prompts, WebSocket URLs, or logs.
