# Data Orchestration Platform

AI 기반 분산 데이터 오케스트레이션 플랫폼. 사용자가 DB만 등록하고 대시보드 또는 자연어로 요청하면 Kafka 토픽과 Debezium Connector가 자동으로 구성된다.

## Architecture

자세한 구조: [docs/architecture/](docs/architecture/)

## 레포 구조

```
data-orchestration-platform/
├─ docs/                      문서, ADR, 명세서
├─ libs/
│   └─ common-dto/            서비스 간 공유 DTO
├─ services/
│   ├─ core-service/          인증, 도메인, REST API, WebSocket (B)
│   ├─ orchestrator-service/  K8s/Kafka 자동화 (C)
│   ├─ ai-service/            LLM 통합 (D)
│   └─ frontend/              React UI (E)
├─ infra/                     Terraform, Helm, K8s yaml (A)
├─ scripts/                   로컬 dev 스크립트
└─ docker-compose.yml         로컬 개발 환경
```

## 서비스 목록

| Service | Port | 역할 | Owner |
| --- | --- | --- | --- |
| core-service | 8080 | 외부 API, 인증, 도메인 | B |
| orchestrator-service | 8081 | K8s/Kafka 자동화 (내부) | C |
| ai-service | 8082 | LLM 통합 (내부) | D |
| frontend | 5173 | React UI | E |

## Quick Start

### Prerequisites

- JDK 21 (Temurin 권장)
- Node 20+
- Docker + Docker Compose
- Gradle 8.10+ (Wrapper 생성용. 한 번만 필요)

### 최초 셋업

```bash
# 1. 클론
git clone https://github.com/your-org/data-orchestration-platform.git
cd data-orchestration-platform

# 2. Gradle Wrapper 생성 (한 번만)
gradle wrapper --gradle-version 8.10

# 3. 의존성 환경 (Kafka, MetaDB, 사용자 DB 시뮬레이터) 띄우기
docker-compose up -d

# 4. Backend 빌드 + 실행
./gradlew :services:core-service:bootRun
# 다른 터미널:
./gradlew :services:orchestrator-service:bootRun
# 다른 터미널 (Sprint 4부터):
./gradlew :services:ai-service:bootRun

# 5. Frontend
cd services/frontend
npm install  # 또는 pnpm install
npm run dev
```

이제 접속:
- Frontend: http://localhost:5173
- core API: http://localhost:8080/swagger-ui.html
- Kafka UI: http://localhost:8090

## Gradle 명령어 참고

```bash
# 전체 빌드
./gradlew build

# 특정 모듈만 빌드
./gradlew :services:core-service:build

# 테스트
./gradlew test
./gradlew :services:core-service:test

# Docker 이미지 빌드 (Spring Boot)
./gradlew :services:core-service:bootBuildImage

# 의존성 트리
./gradlew :services:core-service:dependencies
```

## 개발 워크플로

### 브랜치 전략 (GitHub Flow)

```
main (always deployable)
  └─ feature/B-datasource-api
  └─ feature/C-tenant-provisioner
  └─ fix/login-bug
```

- 모든 변경은 feature 브랜치 → PR → main 머지
- 머지하면 자동 배포 (.github/workflows/)
- main은 직접 푸시 금지 (보호 설정)

### 커밋 메시지

```
[영역] 짧은 설명

자세한 설명 (선택)
```

영역 예: `core`, `orchestrator`, `ai`, `frontend`, `infra`, `common-dto`, `docs`.

### PR 규칙

- 제목: `[core] Add datasource registration API`
- 최소 1명 리뷰
- CI 통과 필수
- 본인 PR은 본인이 머지 (Squash and merge)

## 팀

| Role | Name | 담당 |
| --- | --- | --- |
| Infra Lead | A | EKS, Strimzi, Kafka, CI/CD, 모니터링 |
| Backend Domain | B | core-service |
| K8s Automation | C | orchestrator-service |
| AI / LLM | D | ai-service (Sprint 4~) |
| Frontend | E | frontend |

자세한 R&R: [docs/team/rnr.md](docs/team/rnr.md)

## 문서

- [Architecture Overview](docs/architecture/)
- [MVP Specification](docs/specs/mvp-spec.md)
- [ADR (Architecture Decision Records)](docs/adr/)
- [API Specs](docs/api/)

## License

(캡스줍 종료 후 결정)
