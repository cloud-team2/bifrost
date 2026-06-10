"""FastAPI 앱 엔트리포인트.

실행: `uvicorn app.main:app --port 8082`
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import (
    routes_actions,
    routes_admin,
    routes_agent,
    routes_approvals,
    routes_catalogs,
    routes_change,
    routes_events,
    routes_evidence,
    routes_feedback,
    routes_health,
    routes_reports,
)
from app.api import routes_runs
from app.core.config import settings
from app.core.db import close_pool, init_pool

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        await init_pool(settings.database_url)
    except Exception as exc:
        logger.warning("agentdb unavailable — running without persistence: %s", exc)
    yield
    await close_pool()


def create_app() -> FastAPI:
    app = FastAPI(
        title="Bifrost AI Agent Server",
        version=settings.version,
        description="AI 장애대응 (FastAPI). 운영 조회/조치는 Spring /internal/ops로 위임.",
        lifespan=lifespan,
    )

    # 설계 API 표면: /api/v1
    app.include_router(routes_health.router, prefix="/api/v1", tags=["health"])
    app.include_router(routes_agent.router, prefix="/api/v1/agent", tags=["agent"])
    app.include_router(routes_runs.router, prefix="/api/v1/agent", tags=["runs"])
    app.include_router(routes_events.router, prefix="/api/v1/agent", tags=["events"])
    app.include_router(routes_actions.router, prefix="/api/v1/agent", tags=["actions"])
    app.include_router(routes_approvals.router, prefix="/api/v1/agent", tags=["approvals"])
    app.include_router(routes_approvals.decision_router, prefix="/api/v1", tags=["approvals"])
    app.include_router(routes_change.router, prefix="/api/v1/agent", tags=["change"])
    app.include_router(routes_reports.router, prefix="/api/v1", tags=["reports"])
    app.include_router(routes_feedback.router, prefix="/api/v1/agent", tags=["feedback"])
    app.include_router(routes_admin.router, prefix="/api/v1/admin", tags=["admin"])
    app.include_router(routes_evidence.router, prefix="/api/v1/agent", tags=["evidence"])
    app.include_router(routes_catalogs.router, prefix="/api/v1", tags=["catalogs"])

    # K8s liveness/readiness probe용 경량 엔드포인트 (helm deployment에서 사용)
    @app.get("/health", tags=["health"])
    def health_probe() -> dict:
        return {"status": "ok"}

    return app


app = create_app()
