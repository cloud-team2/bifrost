# Bifrost AI Agent Server (FastAPI)
FROM python:3.11-slim AS base
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AI_PORT=8082

# 빌드에 필요한 메타데이터 + 소스 (hatchling이 app 패키지를 빌드)
COPY pyproject.toml ./
COPY app app
RUN pip install --no-cache-dir .

# DB 마이그레이션 (alembic). 파드/initContainer에서 `alembic upgrade head` 실행에 필요.
# pip 레이어 캐시 보존을 위해 install 이후에 복사한다.
COPY alembic.ini ./
COPY alembic alembic

EXPOSE 8082
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]
