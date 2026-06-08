# CICD 스택 — Jenkins / ArgoCD / Harbor

EKS 클러스터에 Jenkins, ArgoCD, Harbor를 Helm으로 배포하고
ALB Ingress로 외부에 노출하는 구성.

## 파일 구조

```
infra/
├── cicd/
│   ├── deploy.sh              # 배포 스크립트
│   ├── jenkins-values.yaml    # Jenkins Helm values
│   ├── argocd-values.yaml     # ArgoCD Helm values
│   └── harbor-values.yaml     # Harbor Helm values
└── k8s/
    └── ingress/
        ├── harbor-ingress.yaml   # Harbor  ALB (port 80,  group.order 10)
        ├── jenkins-ingress.yaml  # Jenkins ALB (port 8080, group.order 20)
        └── argocd-ingress.yaml   # ArgoCD  ALB (port 8081, group.order 30)
```

## ALB 구성

세 Ingress가 **`cicd-shared-alb`** 단일 ALB를 공유 (`group.name: cicd-shared`).

| 서비스 | 포트 | group.order |
|--------|------|-------------|
| Harbor | 80   | 10          |
| Jenkins | 8080 | 20         |
| ArgoCD | 8081 | 30          |

Public Subnets: `subnet-0d8a1dcc1e064b04d`, `subnet-05e64cd452e9a3c55`

## 배포

```bash
# 전체 배포 (최초 구성 또는 재구성)
./infra/cicd/deploy.sh all

# 개별 배포
./infra/cicd/deploy.sh jenkins
./infra/cicd/deploy.sh argocd
./infra/cicd/deploy.sh harbor
./infra/cicd/deploy.sh ingress
```

## 현재 배포 버전

| 차트 | 버전 | App 버전 |
|------|------|---------|
| jenkins/jenkins | 5.9.22 | 2.555.2 |
| argo/argo-cd | 9.5.17 | v3.4.3 |
| harbor/harbor | 1.18.0 | 2.14.0 |

## 접근 정보

ALB DNS: `cicd-shared-alb-1042102888.ap-northeast-2.elb.amazonaws.com`

| 서비스 | URL |
|--------|-----|
| Harbor  | http://cicd-shared-alb-...:80 |
| Jenkins | http://cicd-shared-alb-...:8080 |
| ArgoCD  | http://cicd-shared-alb-...:8081 |

```bash
# DNS 확인
kubectl get ingress -n harbor harbor-alb \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

## 기본 계정

| 서비스 | ID | Password |
|--------|-----|---------|
| Jenkins | admin | admin |
| ArgoCD | admin | (초기 시크릿) `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" \| base64 -d` |
| Harbor | admin | admin! |
