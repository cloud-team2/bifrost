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
    # /internal/ops service-to-service 인증 토큰(#646). 값이 있으면 ops 호출에 X-Internal-Token 헤더로
    # 동봉한다. 비면 미동봉 → ops-backend도 게이트 비활성이라 로컬/기존 환경 호환. gitops가 양쪽에 동일 시크릿 주입.
    internal_ops_token: str = ""

    # 분산 추적(OTel, #372). 엔드포인트가 비면 비활성 → 로컬/CI는 collector 없이 동작.
    # dev/prod(gitops)는 AI_OTLP_TRACING_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces 로 켜고,
    # ops-backend(#366)와 같은 Collector tail-sampling(#370)을 공유한다.
    otlp_tracing_endpoint: str = ""
    tracing_sample_rate: float = 1.0

    # LLM Provider (역할별 tier는 추후 확장)
    llm_provider: str = "openai"
    llm_api_key: str = ""
    llm_default_model: str = "gpt-4o-mini"

    # Knowledge RAG embeddings
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-3-small"
    # NOTE: alembic/versions/002 의 vector(1536) 컬럼과 반드시 일치해야 한다.
    # 차원 변경 시 새 마이그레이션이 필요(기존 임베딩 재인덱싱). 단순 env 변경만으로는 INSERT가 깨진다.
    embedding_dimensions: int = 1536
    knowledge_search_limit: int = 3
    knowledge_min_score: float = 0.05
    # RCA evidence matching. Embeddings are auxiliary only: catalog required and
    # negative rules still gate confidence. Keep rollout default-off so
    # semantic matching has to be enabled deliberately during A/B evaluation.
    rca_embedding_match_enabled: bool = False
    rca_embedding_match_threshold: float = 0.86
    rca_embedding_match_prefer_openai: bool = True

    # Agent Run Store (agentdb — PostgreSQL)
    database_url: str = "postgresql+asyncpg://agent:agent@localhost:5432/agentdb"

    # Agent run 안전장치 (DETAILS §15.5 루프 방지)
    max_steps_per_run: int = 24
    max_revisions: int = 2
    # 종료 보장 budget — step/loop 카운터 외 시간·자원 예산 (#481)
    wall_clock_timeout_seconds: float = 300.0
    stage_timeout_seconds: float = 120.0
    max_llm_calls_per_run: int = 16
    max_tokens_per_run: int = 200_000


settings = Settings()
