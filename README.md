# Bifrost GitOps 브랜치 (CD 소스)

⚠️ **이 브랜치는 ArgoCD 전용 long-lived 배포 브랜치다. develop/main으로 merge 하지 않는다.**
(Jenkins CI가 image tag를 여기에 직접 commit하고, ArgoCD가 polling으로 감지해 sync한다.)

## 구조
```
charts/        앱 Helm 차트 (ArgoCD helm app) — 이미지: harbor.harbor.svc/library/bifrost-<svc>
  ├─ operations-backend/   Spring Boot (rbac·sa 포함, ALB /api·/ws)
  ├─ ai-service/           FastAPI (ClusterIP 내부)
  └─ frontend/             React/nginx (ALB / )
databases/     in-cluster DB raw manifest (ArgoCD directory app, prune off)
  ├─ metadb/  agentdb/(pgvector)  tenantdb/
infra/         CI/CD ALB ingress helm 차트 (Harbor/Jenkins/ArgoCD)
argocd/        ArgoCD Application (app-of-apps)
  ├─ root.yaml         bifrost-root (argocd/apps 감시)
  └─ apps/             operations-backend·ai-service·frontend·databases·cicd-ingress
```

## CD 흐름 (no webhook, polling)
main 머지 → Jenkins(CI) Kaniko 빌드 → Harbor push → **Jenkins가 charts/<svc>/values.yaml의 image.tag commit** → ArgoCD가 gitops polling(reconcile) → 클러스터 sync.

## 부트스트랩 (1회, 라이브)
```
kubectl apply -f argocd/root.yaml   # app-of-apps → 나머지 Application 자동 생성
```
사전: ArgoCD에 이 repo 연결(GitHub PAT), 각 ns에 harbor-push-secret 복제, operations-backend는 jwt-secret·operations-backend-secrets 필요.
