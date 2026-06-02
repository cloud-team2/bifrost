# ai-service (Bifrost AI Agent Server)

AI 장애대응 계층 (FastAPI). evidence 기반으로 Kafka 파이프라인 장애를 분석·대응 제안하며,
운영 리소스는 직접 만지지 않고 Spring Boot Operations Backend의 `/internal/ops`로 위임한다.

> 설계: `design/backend/fastapi/README.md`, `api.md`, `DETAILS.md`. 결정: [ADR 0004](../../docs/adr/0004-monorepo-monolith.md) (ai-service는 FastAPI).

## 구조

```
services/ai-service/
├─ pyproject.toml
├─ Dockerfile
├─ app/
│  ├─ main.py            # FastAPI 엔트리포인트 (uvicorn app.main:app)
│  ├─ config.py          # Settings (env prefix AI_)
│  ├─ schemas.py         # 공통 응답 봉투 / 에러 코드
│  ├─ api/               # /api/v1 표면 (health, agent runs ...)
│  ├─ agent/             # Supervisor·State / agents(8 LLM) / deterministic(룰 단계)
│  ├─ tools/             # Tool Client Registry → Spring /internal/ops
│  ├─ llm/               # LLM provider tier
│  └─ evidence/          # evidence 메타데이터 (reference only)
└─ tests/
```

## 로컬 실행

```bash
cd services/ai-service
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

uvicorn app.main:app --port 8082 --reload
# 확인
curl localhost:8082/health            # {"status":"ok"}
curl localhost:8082/api/v1/health     # {ok, request_id, data}

pytest
```

## 환경변수 (prefix `AI_`)

| 변수 | 기본값 | 설명 |
|---|---|---|
| `AI_PORT` | 8082 | 서버 포트 |
| `AI_SPRING_OPS_BASE_URL` | http://localhost:8080 | Spring `/internal/ops` 위임 대상 |
| `AI_LLM_PROVIDER` / `AI_LLM_API_KEY` / `AI_LLM_DEFAULT_MODEL` | openai / "" / gpt-4o-mini | LLM 설정 |

## 상태

스캐폴드 — Health/Metadata API와 구조 골격만 동작한다. Supervisor/8 agent/Tool Registry
실제 워크플로는 후속 이슈에서 구현한다. Agent run 엔드포인트는 현재 `NOT_IMPLEMENTED`를 반환한다.
