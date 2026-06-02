"""Bifrost AI Agent Server (FastAPI).

AI 장애대응 계층. evidence 기반으로 Kafka 파이프라인 장애를 분석·대응 제안하며,
운영 리소스는 직접 만지지 않고 Spring Boot Operations Backend의 `/internal/ops`로 위임한다.
설계: design/backend/fastapi/README.md, api.md, DETAILS.md
"""
