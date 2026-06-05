"""애플리케이션 설정 (pydantic-settings).

환경변수 prefix `AI_` 로 오버라이드한다. 예: `AI_PORT=9000`, `AI_SPRING_OPS_BASE_URL=...`.
"""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", env_file=".env", extra="ignore")

    app_name: str = "bifrost-ai-service"
    version: str = "0.1.0"
    catalog_version: str = "0.1.0"
    port: int = 8082

    # Spring Boot Operations Backend — Agent의 모든 운영 조회/조치 위임 대상 (/internal/ops)
    spring_ops_base_url: str = "http://localhost:8080"
    spring_ops_timeout_seconds: float = 10.0

    # LLM Provider (역할별 tier는 추후 확장)
    llm_provider: str = "openai"
    llm_api_key: str = ""
    llm_default_model: str = "gpt-4o-mini"

    # Agent run 안전장치 (DETAILS §15.5 루프 방지)
    max_steps_per_run: int = 24
    max_revisions: int = 2


settings = Settings()
