.PHONY: help \
        tf-init tf-plan tf-apply tf-destroy \
        kubeconfig \
        setup-strimzi setup-metadb setup-userdb setup-infra \
        build-connect \
        local-up local-down

AWS_PROFILE  := skala_student
AWS_REGION   := ap-northeast-2
CLUSTER_NAME := skala3-cloud1-finalproj-team2
TF_DIR       := infra/terraform/environments/dev

help: ## 이 도움말 출력
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ─── Terraform ───────────────────────────────────────────────────────────────

tf-init: ## Terraform 초기화
	cd $(TF_DIR) && terraform init

tf-plan: ## Terraform 변경 사항 미리 보기
	cd $(TF_DIR) && terraform plan

tf-apply: ## AWS 리소스 생성 (EKS)
	cd $(TF_DIR) && terraform apply

tf-destroy: ## AWS 리소스 삭제 (주의: 데이터 손실)
	cd $(TF_DIR) && terraform destroy

tf-output: ## Terraform 출력값 확인
	cd $(TF_DIR) && terraform output

# ─── K8s 접근 ────────────────────────────────────────────────────────────────

kubeconfig: ## EKS kubeconfig 업데이트
	./scripts/setup-kubeconfig.sh

# ─── 인프라 설치 (순서 중요) ──────────────────────────────────────────────────

setup-strimzi: ## Strimzi Operator + Kafka 클러스터 설치
	./scripts/setup-strimzi.sh

setup-metadb: ## MetaDB (플랫폼 내부 PostgreSQL on EBS) 설치
	./scripts/setup-metadb.sh

setup-userdb: ## 소스/싱크 DB 시뮬레이션 설치 (PostgreSQL CDC + MariaDB binlog)
	./scripts/setup-userdb.sh

setup-infra: kubeconfig setup-strimzi setup-metadb setup-userdb ## 전체 K8s 인프라 설치
	@echo ""
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@echo "K8s 인프라 설치 완료"
	@echo "다음 단계: make build-connect"

# ─── Kafka Connect 이미지 ─────────────────────────────────────────────────────

build-connect: ## Kafka Connect 이미지 빌드 후 Docker Hub(hwnnn) push
	./scripts/build-push-connect.sh

# ─── 로컬 개발 ───────────────────────────────────────────────────────────────

local-up: ## 로컬 개발 환경 시작 (docker-compose)
	./scripts/local-up.sh

local-down: ## 로컬 개발 환경 종료
	./scripts/local-down.sh

local-clean: ## 로컬 환경 + 볼륨 삭제
	./scripts/local-down.sh --clean
