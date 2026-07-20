FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ADA_REGISTRY=/var/lib/apartment-defect-ai/registry \
    ADA_MODEL=apartment-defect \
    ADA_HOST=0.0.0.0 \
    ADA_PORT=8000

WORKDIR /app

COPY pyproject.toml README.md ./
COPY python ./python
RUN python -m pip install --no-cache-dir ".[serving,onnx]"

RUN groupadd --system app && useradd --system --gid app --home /app app \
    && mkdir -p /var/lib/apartment-defect-ai/registry \
    && chown -R app:app /app /var/lib/apartment-defect-ai

USER app
VOLUME ["/var/lib/apartment-defect-ai/registry"]
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('ADA_PORT','8000')+'/ready', timeout=3)"

CMD ["python", "-m", "vision_ai.serving_entrypoint"]
