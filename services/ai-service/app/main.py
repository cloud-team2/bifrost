"""FastAPI 앱 엔트리포인트.

실행: `uvicorn app.main:app --port 8082`
"""
from __future__ import annotations

from fastapi import FastAPI

from app.api import routes_agent, routes_events, routes_health
from app.core.config import settings


def create_app() -> FastAPI:
    app = FastAPI(
        title="Bifrost AI Agent Server",
        version=settings.version,
        description="AI 장애대응 (FastAPI). 운영 조회/조치는 Spring /internal/ops로 위임.",
    )

    # 설계 API 표면: /api/v1
    app.include_router(routes_health.router, prefix="/api/v1", tags=["health"])
    app.include_router(routes_agent.router, prefix="/api/v1/agent", tags=["agent"])
    app.include_router(routes_events.router, prefix="/api/v1/agent", tags=["events"])

    # K8s liveness/readiness probe용 경량 엔드포인트 (helm deployment에서 사용)
    @app.get("/health", tags=["health"])
    def health_probe() -> dict:
        return {"status": "ok"}

    return app


app = create_app()
