# CICD 스택 — Jenkins / ArgoCD / Harbor

EKS 클러스터에 Jenkins, ArgoCD, Harbor를 Helm으로 bootstrap하고, Jenkins `bifrost-ci`와 Argo CD GitOps 배포 흐름을 구성한다.

현재 외부 노출 정본은 `gitops` 브랜치 `infra/` Helm chart다. 단일 NLB → ingress-nginx → cert-manager(Let's Encrypt)로 `harbor.skala-ai.com`, `jenkins.skala-ai.com`, `argocd.skala-ai.com`을 노출한다. 이 브랜치의 `infra/k8s/ingress/*-ingress.yaml` ALB manifest와 `deploy.sh ingress`는 초기 bootstrap/이력용이다.

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
        ├── harbor-ingress.yaml   # legacy/bootstrap ALB manifest
        ├── jenkins-ingress.yaml  # legacy/bootstrap ALB manifest
        └── argocd-ingress.yaml   # legacy/bootstrap ALB manifest
```

## 외부 노출

현재 운영 노출은 `gitops` 브랜치의 `infra` chart가 관리한다.

| 서비스 | URL | 내부 서비스 |
|--------|-----|-------------|
| Harbor | `https://harbor.skala-ai.com` | `harbor:80` |
| Jenkins | `https://jenkins.skala-ai.com` | `jenkins:8080` |
| Argo CD | `https://argocd.skala-ai.com` | `argocd-server:80` |

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

GitHub webhook은 `https://jenkins.skala-ai.com/github-webhook/` (push 이벤트)로 등록돼 있어야 한다.

## Jenkinsfile 배포 흐름

1. `bifrost-ci`는 JCasC에서 `*/main`만 checkout하므로 `Jenkinsfile` 내부에 branch gate를 두지 않는다.
2. 직전 성공 빌드 대비 변경 파일을 보고 `services/ai-service`, `services/operations-backend`, `services/frontend` 중 변경된 서비스만 `TO_BUILD`에 넣는다.
3. 앱 서비스는 서비스별 Kaniko 컨테이너에서 Harbor `harbor.harbor.svc.cluster.local/library/bifrost-<svc>:<git-sha>`와 `:latest`로 push한다. `operations-backend`는 멀티모듈 Gradle 때문에 빌드 컨텍스트가 레포 루트이고, 다른 앱 서비스는 각 서비스 디렉터리다.
4. 앱 서비스가 빌드되면 `gitops` 브랜치를 clone하고 `charts/<svc>/values.yaml`의 `image.tag`만 git sha로 갱신해 push한다. Argo CD는 polling/reconcile로 이를 배포한다.
5. `infra/docker/kafka-connect/` 또는 `connect-plugins/` 변경 시 Kafka Connect 커스텀 이미지를 별도 Kaniko 컨테이너에서 빌드해 `bifrost-kafka-connect:1.0.0-converter`, git sha, `latest`로 Harbor에 push한다. 이 단계는 GitOps tag를 자동 변경하지 않는다.

## 현재 배포 버전

`deploy.sh`가 사용하는 chart version 기본값이다. 실제 app version은 chart 릴리스에 종속되므로 여기서 별도 고정하지 않는다.

| 차트 | 버전 |
|------|------|
| jenkins/jenkins | 5.9.22 |
| argo/argo-cd | 9.5.17 |
| harbor/harbor | 1.18.0 |

## 기본 계정

| 서비스 | ID | Password |
|--------|-----|---------|
| Jenkins | admin | admin |
| ArgoCD | admin | (초기 시크릿) `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" \| base64 -d` |
| Harbor | admin | admin! |
