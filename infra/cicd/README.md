# CICD 스택 — Jenkins / ArgoCD / Harbor

EKS 클러스터에 Jenkins, ArgoCD, Harbor를 Helm으로 배포하고
ALB Ingress로 외부에 노출하는 구성.

## 파일 구조

```
infra/
├── cicd/
│   ├── deploy.sh              # 배포 스크립트
│   ├── jenkins-values.yaml    # Jenkins Helm values (+ JCasC로 bifrost-ci job 정의)
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

## CI 파이프라인 Job (bifrost-ci)

CI 파이프라인은 Jenkins의 **`bifrost-ci`** Pipeline job이 실행한다. job 정의는
`jenkins-values.yaml`의 **JCasC(`controller.JCasC.configScripts`, job-dsl)** 에 코드로 보관한다.
`deploy.sh jenkins`(= helm upgrade) 또는 파드 재기동 시 job이 자동 생성·동기화된다.
(UI에서 수동 변경해도 다음 재기동에 JCasC 정의로 원복 = 단일 SoT)

| 설정 | 값 |
|------|-----|
| 종류 | Pipeline script from SCM (job-dsl `pipelineJob`) |
| SCM | `github.com/cloud-team2/bifrost.git` (creds `github-pat`), `*/main` |
| scriptPath | `Jenkinsfile` |
| Lightweight checkout | **false** (필수) |
| 트리거 | GitHub hook (`githubPush()`) |
| 빌드 보관 | 최근 20개 |

> **Lightweight checkout은 반드시 off.** on이면 Jenkinsfile만 가볍게 받아 폴링
> baseline(SCMRevisionState)을 기록하지 않아, webhook poke가 와도 폴링이 변경을
> 감지하지 못해 **자동 빌드가 트리거되지 않는다.** off면 매 빌드가 full checkout으로
> baseline을 남겨 push→자동 빌드가 동작한다.

> `disableConcurrentBuilds`는 `Jenkinsfile`의 `options{}`가 런타임에 설정한다.

GitHub webhook은 `http://<ALB>:8080/github-webhook/` (push 이벤트)로 등록돼 있어야 한다.

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
