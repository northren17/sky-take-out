FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /app
RUN groupadd --system --gid 10001 voice \
    && useradd --system --uid 10001 --gid voice --home-dir /app voice \
    && mkdir -p /app/data/chroma_db /app/logs \
    && chown -R voice:voice /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY agent ./agent
COPY data/policies.json ./data/policies.json
RUN chown -R voice:voice /app
USER voice
EXPOSE 8000
CMD ["uvicorn", "agent.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
