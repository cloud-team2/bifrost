# Infra 설계

> 사람이 읽는 요약본이다. 설계 원리·리소스 계획·현황 전체는 [DETAILS.md](#).

단일 EKS 클러스터 안에 Bifrost를 역할별 namespace로 올린다. 별도 VPC·ECR은 쓰지 않고, 부족한 control plane(Harbor·Jenkins·Argo CD·Kafka Connect·Observability·앱)을 기존 리소스 위에 순차 추가한다.

```mermaid
flowchart TB
    subgraph EKS[Single EKS Cluster]
      direction LR
      PK[platform-kafka<br/>Kafka·Connect·CR]
      BF[bifrost-system<br/>FE·FastAPI·SpringBoot]
      RG[registry<br/>Harbor]
      CI[cicd<br/>Jenkins]
      AR[argocd]
      MON[monitoring<br/>Prom·Grafana·Loki·Tempo]
      MDB[(metadb<br/>Spring 메타·evidence)]
      ADB[(agentdb<br/>FastAPI run·pgvector)]
      UDB[(tenantdb<br/>고객 source/sink)]
    end
    BF --> PK
    BF --> MDB
    BF --> ADB
    BF --> MON
```

## 핵심 결정

| 항목 | 결정 |
| --- | --- |
| 클러스터 | 단일 EKS + namespace·정책 분리(dev/stage/prod 물리 분리 없음) |
| Registry | ECR 불가 → **Harbor**(in-cluster). PVC Retain |
| CI/CD | **Jenkins**(build/push) + **Argo CD**(GitOps deploy) |
| Kafka | Strimzi **KRaft 3노드**(`platform-kafka`, RF3/minISR2, combined controller+broker). ZooKeeper 없음 |
| Listener | scram `9094`(SCRAM-SHA-512, TLS) 표준. plain `9092`는 운영 전 제거/제한 |
| 권한 경계 | Agent는 K8s/Kafka credential 없음. Spring Boot Operations Backend만 제한 권한으로 런타임 접근 |
| Evidence Store | `metadb`의 **PostgreSQL 기본**(대용량 blob 필요 시 MinIO 추가) |

## Namespace

`platform-kafka`(Kafka·Connect·CR) · `bifrost-system`(FE·FastAPI·SpringBoot) · `registry`(Harbor) · `cicd`(Jenkins) · `argocd` · `monitoring`(Prometheus·Alertmanager·Grafana·Loki·Tempo·exporters) · `metadb`(Spring 메타·evidence) · `agentdb`(FastAPI run·pgvector 벡터) · `tenantdb`(고객 source/sink)

## 현재 상태 (2026-06-05)

| 완료 | 미구성 |
| --- | --- |
| **EKS 5노드 단일 풀(t3.xlarge, #119)**, Strimzi Operator, `platform-kafka` Ready(3 broker/controller, PVC 3), 내부 topic 3, **Kafka Connect `platform-connect`(1 replica)**, **Harbor**(8 pod), **Jenkins**, **Argo CD**(앱 0개), gp3 default, metadb/**agentdb(pgvector, #120)**/tenantdb, `bifrost-system` ns + ai-service helm·secret 준비 | Monitoring(Prometheus/Grafana/Loki/Tempo·exporter), 앱 **배포**(FastAPI/Spring/Frontend — 이미지 CICD 빌드 대기), Evidence/Audit Store **스키마**, KafkaConnector/KafkaUser, metrics-server, IngressClass |

> ⚠️ Harbor·Jenkins·Argo CD·Kafka Connect는 **수동 배포**되어 있고 **manifest가 repo에 YAML로 미반영**이다(Argo CD Application 0개 = GitOps 미연동). manifest 역추출·GitOps 연동은 후속 작업. 상세 스냅샷은 [§2 추가 배포 현황](#35-추가-배포-현황-2026-06-02-스냅샷-수동-배포).
> ⚠️ **앱 이미지 배포 경로**: ai-service 등 앱은 **Harbor(in-cluster)** 에 올린다(Docker Hub 아님). 정석 플로우 = Jenkins CI 빌드(Kaniko/buildah, docker 미사용)→Harbor push→GitOps manifest tag 갱신→ArgoCD 배포(#123). 노트북 docker 수동 push는 지양. Harbor는 ALB(HTTP)·내부 DNS `harbor.harbor.svc.cluster.local/library/<image>` pull, `harbor-push-secret`(dockerconfigjson) imagePullSecret.

## 다음 우선순위

(완료: Harbor·Jenkins·Argo CD·Kafka Connect 수동 배포) → **manifest 역추출·GitOps(Argo CD Application) 연동** → Monitoring(+Loki/Tempo) → Evidence/Audit/Metadata Store → KafkaConnector/KafkaUser → Spring Boot → FastAPI → Frontend.

## 점검 필요 (운영 전)

`auto.create.topics.enable=true` 끄기 · plain listener 제한(scram 표준화) · tenantdb LoadBalancer 노출 재검토 · PDB/anti-affinity · gp3 Retain(orphan PV) 정책 · **클러스터 용량: 3×t3.large CPU 요청 ~81%로 포화 임박, 남은 스택(monitoring·앱) 수용 불가 → 노드 확장/인스턴스 상향 필요([§2 §11 용량 분석](#11-클러스터-용량-분석-및-대응안-2026-06-02))**.

## 더 읽기 → [DETAILS.md](#)

[1 설계 원리](#1-설계-원리-design-principles) · [2 리소스 계획·현황](#2-리소스-계획현황-resource-plan)


---


> 요약은 [README.md](#). 설계 원리([§1](#1-설계-원리-design-principles))와 리소스 계획·현황([§2](#2-리소스-계획현황-resource-plan))을 병합한 전체 상세다. 문서 내 옛 상호링크는 목차의 섹션으로 대체됐다.

## 목차
1. [설계 원리 (Design Principles)](#1-설계-원리-design-principles)
2. [리소스 계획·현황 (Resource Plan)](#2-리소스-계획현황-resource-plan)

---

## 1. 설계 원리 (Design Principles)


### 1. 목적

이 문서는 Bifrost를 하나의 EKS 클러스터 위에 배치하기 위한 인프라 원칙을 정의한다. 기존 문서가 장애 대응을 위해 무엇을 관측할지에 집중했다면, 이 문서는 어떤 runtime boundary 안에서 Agent, Backend, Kafka, CI/CD, Registry, Observability를 구성할지를 다룬다.

구체적인 Kubernetes 리소스 목록과 현재 진행 상태는 [§2 Resource Plan](#2-리소스-계획현황-resource-plan)에 둔다. Spring Boot API와 Agent tool은 각각 [Spring Boot DETAILS](./backend-springboot/overview.md), [FastAPI DETAILS](./backend-fastapi/overview.md)를 기준으로 한다.

### 2. 제약사항

현재 인프라 설계는 다음 제약을 전제로 한다.

| 제약 | 설계 영향 |
| --- | --- |
| 하나의 EKS 클러스터만 사용 | dev/stage/prod를 물리 클러스터로 분리하지 않고 namespace와 policy로 분리 |
| 별도 VPC 생성 불가 | 기존 EKS/VPC/Subnet/LoadBalancer 범위 안에서만 구성 |
| ECR 사용 불가 | 클러스터 내부 Harbor Registry를 별도 namespace에 배포 |
| CI/CD는 Jenkins와 Argo CD 사용 | Jenkins는 build/push, Argo CD는 GitOps deploy 담당 |
| 운영 리소스는 이미 일부 존재 | 현재 리소스를 보존하면서 Kafka Connect, Registry, CI/CD, Observability를 추가 |

이 제약 때문에 “AWS managed service를 새로 붙이는 설계”보다 “기존 EKS 안에 필요한 control plane을 올리는 설계”가 우선이다.

### 3. 전체 배치 구조

```text
Single EKS Cluster
  ├─ platform-kafka
  │   ├─ Strimzi Kafka
  │   ├─ Kafka Connect
  │   ├─ KafkaTopic / KafkaUser / KafkaConnector
  │   └─ KafkaRebalance / Cruise Control
  │
  ├─ bifrost-system
  │   ├─ Frontend
  │   ├─ FastAPI Agent Server
  │   ├─ Spring Boot Operations Backend
  │   └─ application config / service account
  │
  ├─ registry
  │   └─ Harbor
  │
  ├─ cicd
  │   ├─ Jenkins
  │   └─ Jenkins build agents
  │
  ├─ argocd
  │   └─ Argo CD
  │
  ├─ monitoring
  │   ├─ Prometheus / Alertmanager
  │   ├─ Grafana
  │   ├─ Loki / Promtail
  │   ├─ Tempo
  │   └─ Kafka exporter / JMX exporter
  │
  ├─ metadb
  │   └─ metadata / audit / evidence DB
  │
  └─ tenantdb
```

Namespace 이름은 실제 배포 과정에서 조정할 수 있지만, 역할별 경계는 유지한다.

### 4. 책임 경계

| Plane | 구성 | 책임 |
| --- | --- | --- |
| Control Plane | Frontend, FastAPI Agent, Spring Boot Backend | 분석, 승인, 정책, 감사, 운영 API |
| Data Plane | Kafka, Kafka Connect, connector, consumer, pipeline worker | 데이터 이동과 처리 |
| Delivery Plane | Jenkins, Harbor, Argo CD | build, image registry, GitOps 배포 |
| Observability Plane | Prometheus, Loki, Tempo, Grafana, Kubernetes event | metric/log/trace/event 수집 |
| Storage Plane | EBS PVC, metadata DB, evidence store | 상태 저장 |

Agent는 Data Plane을 직접 제어하지 않는다. Agent의 실행 요청은 Spring Boot Operations Backend를 통과해야 한다.

Project scope는 Kubernetes label/annotation, Kafka topic/user naming, pipeline registry metadata로 표현한다. 같은 EKS 클러스터를 공유하더라도 모든 운영 API는 `project_id`와 resource ownership을 함께 검증해야 한다.

### 5. Kafka 배치 원칙

Kafka는 Bifrost의 핵심 data plane이다. v1은 Strimzi 기반 Kafka를 사용한다.

#### 5.1 MVP 구조

현재 클러스터 상태와 리소스 제약을 고려하면 MVP는 다음 구조가 현실적이다.

```text
Kafka cluster: platform-kafka
  ├─ 3 replicas
  ├─ KRaft mode
  ├─ combined controller + broker node pool
  ├─ internal listener only
  ├─ replication factor 3
  └─ min.insync.replicas 2
```

이 구조는 단일 EKS 클러스터와 3개 노드 환경에서 시작하기에 적합하다. 다만 broker와 controller role이 합쳐져 있으므로 production-grade 분리 구조는 아니다.

#### 5.2 권장 확장 구조

노드 여유가 생기면 KafkaNodePool을 분리한다.

```text
KafkaNodePool controllers
  ├─ replicas: 3
  └─ roles: controller

KafkaNodePool brokers
  ├─ replicas: 3
  └─ roles: broker
```

단일 EKS 클러스터 안에서만 확장한다. 별도 Kafka 전용 VPC나 별도 managed Kafka cluster는 사용하지 않는다.

#### 5.3 Kafka Connect

Kafka Connect는 broker와 분리된 stateless workload로 둔다.

권장 구조:

- `KafkaConnect` replicas 2 이상
- connector plugin이 포함된 custom image 사용
- custom image는 Harbor에 저장
- `KafkaConnector` CR로 connector lifecycle 관리
- Connect REST는 cluster internal로만 노출
- connector offset/config/status topic은 replication factor 3

Agent는 Kafka Connect REST를 직접 호출하지 않는다. Spring Boot Operations Backend가 제한된 API로 호출한다.

### 6. Image Registry

ECR을 사용할 수 없으므로 Harbor를 클러스터 내부에 배포한다.

Harbor의 역할:

- Bifrost application image 저장
- Kafka Connect plugin image 저장
- Jenkins build 결과 push 대상
- Argo CD 배포 image source

초기 Harbor 자체 이미지는 public registry에서 가져올 수밖에 없다. Harbor 설치 후에는 Bifrost 애플리케이션 이미지를 Harbor로 모은다.

주의:

- Harbor PVC는 Retain 정책을 사용한다.
- TLS 또는 내부 CA 정책을 정해야 한다.
- 모든 application namespace에 imagePullSecret을 배포한다.
- Harbor를 외부 공개해야 한다면 기존 EKS/VPC 안의 LoadBalancer 또는 Ingress만 사용한다.

### 7. CI/CD

CI/CD는 Jenkins와 Argo CD로 분리한다.

```text
Developer push
  -> Git repository
  -> Jenkins build/test
  -> Harbor image push
  -> GitOps manifest update
  -> Argo CD sync
  -> EKS deploy
```

Jenkins는 image build와 test만 담당한다. Kubernetes 배포는 Argo CD가 담당한다.

Argo CD는 다음 애플리케이션을 관리한다.

- FastAPI Agent
- Spring Boot Operations Backend
- Frontend
- Kafka Connect connector manifest
- Monitoring stack
- Harbor 설정 일부

Kafka cluster 자체와 Strimzi Operator는 bootstrap 단계에서는 수동 적용될 수 있지만, 안정화 후에는 GitOps 관리 대상으로 전환한다.

### 8. Observability와 Evidence

장애 대응 Agent가 의미 있게 동작하려면 다음 관측 계층이 필요하다.

| 구성 | 목적 |
| --- | --- |
| Prometheus | Kubernetes/Kafka/application metric |
| Alertmanager | alert routing |
| Grafana | dashboard |
| Loki / Promtail | pod/application log |
| Tempo | distributed trace와 connector task trace summary |
| Kafka exporter / JMX exporter | broker/topic/consumer 지표 |
| Kubernetes event 수집 | scheduling, OOM, image pull, eviction 증거 |
| Evidence Store | Agent가 참조할 raw evidence 저장 |

Evidence Store는 Observability backend와 다르다. Observability는 운영 데이터 원천이고, Evidence Store는 특정 Agent run에서 사용한 증거 snapshot을 보존하는 저장소다.

Metadata, audit, evidence 저장소는 `metadb` namespace에 모으는 것을 기본으로 한다. `bifrost-system`에는 FastAPI, Spring Boot, Frontend 같은 application workload만 둔다.

### 9. Network와 노출 정책

기본 원칙은 internal-first다.

| 리소스 | 노출 원칙 |
| --- | --- |
| Kafka broker | ClusterIP internal listener |
| Kafka Connect REST | ClusterIP |
| Spring Boot Operations API | ClusterIP 또는 내부 Ingress |
| FastAPI Agent | Frontend/backend 내부 통신 우선 |
| Harbor | Jenkins/Argo CD 접근 가능, 필요 시 제한적 외부 노출 |
| Argo CD | 운영자 접근 필요, 인증 필수 |
| Jenkins | 운영자 접근 필요, 인증 필수 |
| DB | 기본 ClusterIP 권장 |

현재 일부 DB service가 LoadBalancer로 노출되어 있다. 데모 목적이 아니라면 ClusterIP 또는 접근 제한 방식으로 바꾸는 것을 검토한다.

### 10. Storage

기본 StorageClass는 gp3를 사용한다.

권장 원칙:

- Kafka broker PVC는 `deleteClaim: false`
- Harbor registry storage는 Retain
- metadata/evidence DB는 backup 전략 필요
- Kafka topic 데이터와 Evidence Store 데이터의 retention을 분리
- PVC 확장을 고려해 gp3 사용

### 11. 보안 원칙

1. FastAPI Agent는 Kubernetes credential을 갖지 않는다.
2. Spring Boot Operations Backend만 제한된 runtime 권한을 갖는다.
3. Kafka 외부 listener는 기본적으로 만들지 않는다.
4. Secret 원문은 Agent와 Report에 노출하지 않는다.
5. Jenkins credential은 namespace와 service account로 분리한다.
6. Argo CD는 GitOps source of truth를 기준으로 배포한다.
7. Harbor pull secret은 필요한 namespace에만 배포한다.

### 12. 현재 상태 요약

현재 상태(완료/미구성)와 진행 상황은 **[§2 리소스 계획·현황](#2-리소스-계획현황-resource-plan)**의 "현재 클러스터 스냅샷"·"진행 상태"가 단일 출처다(여기서 중복 기술하지 않는다).

### 13. 결론

Infra 설계의 핵심은 단일 EKS 클러스터라는 제약 안에서 Kafka data plane, Agent control plane, CI/CD, Registry, Observability를 역할별 namespace로 분리하는 것이다.

Kafka는 현재 3-node KRaft 기반 MVP 구조까지 진행되어 있다. 다음 우선순위는 Harbor, Jenkins/Argo CD, Kafka Connect, Observability, Bifrost application 배포 순서로 control plane을 완성하는 것이다.

---

## 2. 리소스 계획·현황 (Resource Plan)


### 1. 목적

이 문서는 단일 EKS 클러스터 위에 Bifrost 운영 Agent 시스템을 띄우기 위해 필요한 Kubernetes 리소스와 현재 진행 상태를 정리한다.

작성 기준:

- 하나의 EKS 클러스터만 사용
- 별도 VPC 생성 불가
- ECR 사용 불가
- Harbor를 in-cluster image registry로 사용
- Jenkins와 Argo CD로 CI/CD 구성
- 기존 Kubernetes 리소스는 최대한 보존

### 2. 현재 클러스터 스냅샷

`kubectl`로 확인한 context:

```text
arn:aws:eks:ap-northeast-2:881490135253:cluster/skala3-cloud1-finalproj-team2
```

노드 (#119 — 단일 노드풀로 통합, 2026-06-05):

| 항목 | 현재 상태 |
| --- | --- |
| worker node | **5개 Ready** (t3.xlarge, 4vCPU/16Gi) — 구 3× t3.large에서 스펙업 |
| 노드풀 | **단일 풀** t3.xlarge ×5 (desired 5/min 3/max 7), taint 없음. 전 워크로드 공용 |
| Kafka 배치 | pod **anti-affinity로 broker 노드 분산**(HA). 노드 풀 격리는 안 함 |
| AZ 분포 | 2 AZ(2a×3 / 2b×2). 5노드라 2a 노드 3개 → 2a broker(kafka-0·2)가 서로 다른 노드로 분산됨 |
| AWS 노드그룹명 | `...-ng-data` (단일 풀이지만 재생성 churn 회피로 기존 키 유지) |
| Kubernetes version | v1.35 |
| OS | Amazon Linux 2023 |
| container runtime | containerd |

> **노드풀 토폴로지 결정 (#119)**: 처음엔 Kafka 격리를 위해 data/app 2풀로 나눴으나, **부하가 작아(총 requests ~5.7 vCPU) 물리 분리 실익이 적고 복잡도만 늘어** → **단일 풀로 통합**. 핵심은 "노드 풀 격리"가 아니라 **"Kafka broker를 서로 다른 노드에 분산(anti-affinity)"** — 이것만으로 단일 노드 장애 시 broker 1개만 영향(HA). DB(metadb·tenantdb)·우리 서비스·모니터링·CI/CD는 전부 같은 풀에서 공용. terraform `module.eks`는 `var.node_groups` map + `for_each`라 풀 추가/축소가 한 줄.

> **AZ 주의**: 클러스터는 **처음부터 2 AZ**(ap-northeast-2a/2b)다. `private_subnet_ids`("변경 금지")에 서브넷이 2개(2a·2b) 들어있고, EKS 노드그룹이 그 AZ로 자동 분산한다. #119는 AZ를 추가한 게 아니라 기존 2개 서브넷을 그대로 사용한다. (단일 AZ는 그 AZ 장애 시 전체 다운 + Kafka RF3 AZ 분산 손실 → 멀티 AZ 유지 권장)

> **Kafka broker HA**: broker PVC는 AZ 고정(2a·2a·2b). 단일 풀 5노드는 2a 노드가 3개라 **2a broker 2개가 서로 다른 노드에 배치**되어, 노드 1대 장애 시 broker 1개만 영향(정상 HA). 풀을 3노드 이하로 줄이면 2a broker가 한 노드에 겹칠 수 있으니 주의(`min_size=3` 유지).

> **사이징 메모**: 총 requests ≈ 5.7 vCPU(우리 monolith는 아직 미배포, 부하 대부분이 CI/CD·Kafka). **5노드**(allocatable ~19.5 vCPU)로 운영 — 모니터링 스택(~3~6 vCPU)까지 여유. CI/CD(Harbor·Jenkins·ArgoCD)가 무거우니 빡빡하면 `desired_size` 한 줄로 증설(무중단 in-place).

**검증 명령어 (#119 노드풀)** — `AWS_PROFILE=skala_student AWS_REGION=ap-northeast-2`, `C=skala3-cloud1-finalproj-team2`:

```bash
# 노드풀/노드 (단일 풀 ng-data, 전부 t3.xlarge 5대)
aws eks list-nodegroups --cluster-name $C
kubectl get nodes -L topology.kubernetes.io/zone,node.kubernetes.io/instance-type
aws eks describe-nodegroup --cluster-name $C --nodegroup-name $C-ng-data \
  --query 'nodegroup.{type:instanceTypes,scaling:scalingConfig}'
# Kafka broker가 서로 다른 노드에 분산됐는지 (HA 핵심)
kubectl -n platform-kafka get pods -l strimzi.io/pool-name=kafka -o wide
# 용량 여유(노드별 requests 할당률)
kubectl describe nodes | sed -n '/Allocated resources/,/Events/p' | grep -E 'cpu|memory'
```

Namespace:

| Namespace | 상태 | 용도 추정 |
| --- | --- | --- |
| `default` | Active | 기본 |
| `kube-system` | Active | EKS system |
| `kube-public` | Active | Kubernetes 기본 |
| `kube-node-lease` | Active | Kubernetes 기본 |
| `strimzi-system` | Active | Strimzi operator |
| `platform-kafka` | Active | Kafka cluster + Kafka Connect |
| `metadb` | Active | Spring metadata / audit / evidence DB |
| `agentdb` | Active | FastAPI Agent Run Store + Knowledge Vector Store (pgvector) — #120 |
| `tenantdb` | Active | 고객(테넌트) source/sink DB (데모) |
| `bifrost-system` | Active | 앱(FE·FastAPI·Spring) — ns·ai-service helm/secret 준비, 배포는 CICD 대기 |
| `harbor` | Active | Harbor registry (수동 배포) |
| `jenkins` | Active | Jenkins (수동 배포, 25h) |
| `argocd` | Active | Argo CD (수동 배포, 25h, App 0개) |

### 3. 현재 설치된 핵심 리소스

#### 3.1 EKS 기본 구성

| 리소스 | 현재 상태 |
| --- | --- |
| `aws-node` DaemonSet | 5/5 Running (노드 5) |
| `kube-proxy` DaemonSet | 5/5 Running |
| `coredns` Deployment | 2/2 Running |
| `ebs-csi-controller` Deployment | 2/2 Running |
| `ebs-csi-node` DaemonSet | 5/5 Running |

#### 3.2 StorageClass

| StorageClass | Provisioner | ReclaimPolicy | Expansion | 상태 |
| --- | --- | --- | --- | --- |
| `gp3` | `ebs.csi.aws.com` | Retain | true | default |
| `gp2` | `kubernetes.io/aws-ebs` | Delete | false | legacy |

권장: 새 PVC는 `gp3`를 사용한다.

#### 3.3 Strimzi

| 리소스 | 현재 상태 |
| --- | --- |
| Strimzi CRD | 설치됨 |
| `strimzi-cluster-operator` | 1/1 Running |
| Kafka CRD | 설치됨 |
| KafkaConnect CRD | 설치됨 |
| KafkaConnector CRD | 설치됨 |
| KafkaRebalance CRD | 설치됨 |

#### 3.4 Kafka Cluster

현재 Kafka cluster:

| 항목 | 값 |
| --- | --- |
| namespace | `platform-kafka` |
| Kafka CR | `platform-kafka` |
| Kafka version | `4.2.0` |
| metadata version | `4.2-IV0` |
| mode | KRaft |
| status | Ready |
| node pool | `kafka` |
| replicas | 3 |
| roles | `controller`, `broker` combined |
| storage | 50Gi gp3 PVC per broker |
| replication factor | 3 |
| min ISR | 2 |

Listener:

| listener | port | tls | auth | exposure |
| --- | --- | --- | --- | --- |
| `plain` | 9092 | false | none | internal |
| `scram` | 9094 | true | SCRAM-SHA-512 | internal |

현재 Kafka pod:

| Pod | 상태 |
| --- | --- |
| `platform-kafka-kafka-0` | Running |
| `platform-kafka-kafka-1` | Running |
| `platform-kafka-kafka-2` | Running |

현재 Kafka PVC:

| PVC | Capacity | StorageClass |
| --- | --- | --- |
| `data-0-platform-kafka-kafka-0` | 50Gi | gp3 |
| `data-0-platform-kafka-kafka-1` | 50Gi | gp3 |
| `data-0-platform-kafka-kafka-2` | 50Gi | gp3 |

현재 KafkaTopic:

| Topic CR | partitions | replication factor | ready |
| --- | --- | --- | --- |
| `platform-internal-connector-status` | 3 | 3 | True |
| `platform-internal-service-discovered` | 3 | 3 | True |
| `platform-internal-service-lag-updated` | 3 | 3 | True |

#### 3.5 추가 배포 현황 (2026-06-02 스냅샷, 수동 배포)

> ⚠️ 아래 리소스는 **수동(kubectl/Helm)으로 배포**된 상태이며 **manifest가 repo에 YAML로 반영되어 있지 않다**. manifest 역추출과 GitOps(Argo CD) 연동은 후속 작업이다(Argo CD는 설치되어 있으나 등록된 Application이 0개다).

| Namespace | 워크로드 | 상태 |
| --- | --- | --- |
| `harbor` | Harbor 8 pod (core·database·jobservice·nginx·portal·redis·registry[2/2]·trivy) | 정상 Running. PVC: registry 50Gi·db 10Gi·trivy 10Gi·redis 5Gi·jobservice 5Gi (gp3 Bound) |
| `jenkins` | `jenkins-0` (2/2, StatefulSet) | 정상. PVC 20Gi |
| `argocd` | Argo CD 7 pod (server·repo-server·application/applicationset/notifications-controller·dex·redis) | 정상. **등록 Application 0개(GitOps 미연동)** |
| `platform-kafka` | `platform-connect` (KafkaConnect CR, **replicas 1** READY) + entity-operator | Connect 가동. 목표 2 replica, connector plugin image는 Harbor 기반 재정의 예정. **KafkaConnector/KafkaUser CR은 아직 없음** |

여전히 미구성: `monitoring`(Prometheus/Grafana/Loki/Tempo·exporter), `bifrost-system`(FE/FastAPI/Spring 앱), Evidence/Audit Store, KafkaConnector/KafkaUser CR, metrics-server(`kubectl top` 불가), IngressClass.

**이상 징후: 없음.** 모든 pod Running, Pending/CrashLoop 0, 과다 재시작 0. harbor-database(1회)·harbor-jobservice(3회)·jenkins-0(3회)·argocd-dex(2회)에 기동 시점 소수 재시작이 있으나 의존성 기동 순서에 따른 정상 범위다. **Harbor 8 pod는 Harbor 표준 구성요소로 과다하지 않다**(각 pod가 별개 컴포넌트). trivy(취약점 스캐너)만 선택적이라 불필요 시 비활성화해 리소스를 줄일 수 있다.

### 4. 현재 수정 검토가 필요한 항목

#### 4.1 Kafka `auto.create.topics.enable`

현재 Kafka config에는 `auto.create.topics.enable: true`가 있다.

MVP 초기에는 편하지만, 운영 기준으로는 topic이 의도치 않게 생성될 수 있다. GitOps로 `KafkaTopic`을 관리할 계획이라면 bootstrap 이후 `false`로 전환하는 것을 권장한다.

#### 4.2 Plain listener

현재 `plain` listener가 internal로 열려 있다.

완전히 내부 통신만 한다면 유지 가능하지만, 운영 기준으로는 `scram` listener 사용을 표준으로 두고 plain listener는 제거 또는 제한하는 것이 좋다.

#### 4.3 Combined controller/broker node pool

현재 KafkaNodePool은 3개 replica가 controller와 broker role을 동시에 수행한다.

MVP로는 적절하다. 다만 리소스 여유가 생기면 controller와 broker node pool을 분리한다.

#### 4.4 DB LoadBalancer 노출

현재 `tenantdb` namespace의 MariaDB/Postgres service가 LoadBalancer로 노출되어 있다.

데모 또는 외부 접속 목적이면 유지할 수 있지만, 운영 기준으로는 ClusterIP 전환 또는 접근 제한을 검토한다.

#### 4.5 Kafka Connect — 배포됨(보강 필요)

KafkaConnect CR `platform-connect`가 **replicas 1**로 가동 중이다([§3.5](#35-추가-배포-현황-2026-06-02-스냅샷-수동-배포)). 다만 (a) 목표 replicas 2로 증설, (b) connector plugin 포함 custom image를 Harbor 기반으로 재정의, (c) source/sink **KafkaConnector CR**와 워크스페이스 **KafkaUser CR** 생성이 남았다.

#### 4.6 CI/CD와 Registry — 배포됨(수동, GitOps 미연동)

Harbor·Jenkins·Argo CD가 각각 `harbor`/`jenkins`/`argocd` namespace에 **수동 배포**되어 정상 가동 중이다([§3.5](#35-추가-배포-현황-2026-06-02-스냅샷-수동-배포)). 남은 일: (a) **Argo CD Application 등록**(현재 0개)으로 GitOps 연동, (b) Jenkins build→Harbor push→manifest tag update 파이프라인 구성, (c) 이 리소스들의 **manifest를 repo에 YAML로 역추출**(현재 미반영).

#### 4.7 Observability 미구성

Prometheus, Grafana, Loki, Tempo, Kafka exporter 계열 리소스는 확인되지 않았다.

Agent RCA를 위해 metric/log/trace/event 수집 계층이 필요하다.

#### 4.8 Schema Registry — 설계 참조 있으나 미계획

Spring Boot adapter([server.md §11](./backend-springboot/server.md#11-resource-adapter))·API([api/springboot.md §18](../api/springboot.md#18-schema-registry-api))와 Agent catalog(`SCHEMA_MISMATCH`·`get_schema_changes`)가 **Schema Registry**를 참조하지만, 현재 namespace·리소스 계획·배포 순서 어디에도 없다. v1 Debezium은 schemaless JSON으로도 동작하므로 필수는 아니나, schema 호환성 기반 RCA를 쓰려면 Apicurio/Confluent Schema Registry를 `platform-kafka`에 추가해야 한다. **도입 여부 결정 필요**(미도입 시 관련 tool/RCA 후보를 비활성화).

### 5. Target Namespace Plan

| Namespace | 목적 | 현재 상태 |
| --- | --- | --- |
| `strimzi-system` | Strimzi operator | 존재 |
| `platform-kafka` | Kafka, Kafka Connect, Kafka topic/user/rebalance | 일부 존재 |
| `bifrost-system` | Frontend, FastAPI Agent, Spring Boot Backend | ns 생성·ai-service helm/secret 준비(#120), 배포 CICD 대기 |
| `harbor` | Harbor (registry) — 계획상 `registry`였으나 실제 ns명 `harbor` | 존재(수동) |
| `jenkins` | Jenkins — 계획상 `cicd`였으나 실제 ns명 `jenkins` | 존재(수동) |
| `argocd` | Argo CD | 존재(수동, App 0개) |
| `monitoring` | Prometheus, Grafana, Loki, exporters | 필요 |
| `metadb` | Spring metadata / audit / evidence DB | 존재 |
| `agentdb` | FastAPI Agent Run Store + Knowledge Vector Store(pgvector) | 존재(#120, 스키마는 앱 마이그레이션) |
| `tenantdb` | 고객(테넌트) source/sink DB (데모) | 존재 |

### 6. Target Resource Plan

#### 6.1 Kafka / Strimzi

| 리소스 | 수량/구성 | 상태 |
| --- | --- | --- |
| Strimzi Cluster Operator | 1 replica | 완료 |
| Kafka CR | `platform-kafka` | 완료 |
| KafkaNodePool | 3 combined controller/broker | 완료 |
| Kafka broker/controller pods | 3 | 완료 |
| KafkaTopic CR | platform internal topics | 일부 완료 |
| KafkaUser CR | service user별 생성 | 필요 |
| KafkaConnect CR | 2 replicas 이상 | 필요 |
| KafkaConnector CR | source/sink connector별 생성 | 필요 |
| KafkaRebalance CR | 필요 시 생성 | 필요 |
| Cruise Control | KafkaRebalance 사용 시 Kafka spec에 추가 | 필요 |

#### 6.2 Kafka Connect 목표 구조

```text
platform-kafka namespace
  ├─ Kafka: platform-kafka
  ├─ KafkaNodePool: kafka
  ├─ KafkaConnect: platform-connect
  │   ├─ replicas: 2
  │   ├─ image: harbor/.../kafka-connect:<tag>
  │   ├─ config.storage.replication.factor: 3
  │   ├─ offset.storage.replication.factor: 3
  │   └─ status.storage.replication.factor: 3
  └─ KafkaConnector
      ├─ source connectors
      └─ sink connectors
```

Kafka Connect image는 connector plugin을 포함해 빌드한다. 런타임에 pod 내부로 plugin을 주입하는 방식보다 Harbor에 plugin 포함 image를 올리는 방식이 재현성이 좋다.

#### 6.3 Harbor

| 리소스 | 구성 |
| --- | --- |
| Namespace | `harbor` (계획상 `registry`) |
| Deployment/Stateful workload | Harbor core, registry, portal, jobservice |
| DB | embedded 또는 external PostgreSQL |
| Redis | embedded 또는 external |
| PVC | registry storage, DB storage |
| Service | ClusterIP + 제한적 external access |
| Secret | admin password, TLS, robot account |

Harbor에 저장할 image:

- `bifrost/frontend`
- `bifrost/fastapi-agent`
- `bifrost/springboot-ops`
- `bifrost/kafka-connect`
- Jenkins build agent image

#### 6.4 Jenkins

| 리소스 | 구성 |
| --- | --- |
| Namespace | `jenkins` (계획상 `cicd`) |
| Controller | 1 replica |
| Agent | Kubernetes dynamic agent 권장 |
| PVC | Jenkins home |
| Service | 내부 또는 제한적 외부 접근 |
| Credential | Git, Harbor robot account, Argo CD token |

Jenkins 책임:

1. test
2. image build
3. Harbor push
4. manifest repository tag update

Jenkins가 직접 production workload를 apply하지 않는다.

#### 6.5 Argo CD

| 리소스 | 구성 |
| --- | --- |
| Namespace | `argocd` |
| Application | app별 또는 namespace별 분리 |
| Project | platform / application 구분 |
| Repo | manifest repository |
| Sync | manual 또는 automated 정책 선택 |

Argo CD 관리 대상:

- Bifrost application
- Kafka Connect
- KafkaConnector
- Monitoring stack
- Harbor 설정 일부
- Spring/FastAPI config

Strimzi Operator와 Kafka cluster 자체도 최종적으로 GitOps에 포함하는 것이 좋다.

#### 6.6 Bifrost Application

| 리소스 | Namespace | 구성 |
| --- | --- | --- |
| Frontend | `bifrost-system` | Deployment, Service |
| FastAPI Agent | `bifrost-system` | Deployment, Service, HPA optional |
| Spring Boot Operations Backend | `bifrost-system` | Deployment, Service |
| Evidence Store | `metadb` | **PostgreSQL 기본**(redacted summary·snapshot·reference는 행 크기가 작음). 대용량 원문 blob이 필요해지면 MinIO를 추가하고 PostgreSQL에는 object key만 둔다 |
| Audit Store | `metadb` | PostgreSQL 권장 |
| Metadata Store | `metadb` | PostgreSQL 권장 |
| Agent Run Store | `agentdb` | **FastAPI 전용 PostgreSQL**(`pgvector/pgvector:pg16`) — run/state/event/approval/report. Spring metadb와 분리(서비스 경계, [fastapi §9](./backend-fastapi/server-design.md#2-server-design)) |
| Knowledge Vector Store | `agentdb` | RAG 코퍼스 임베딩 — **pgvector 확장**으로 같은 agentdb 인스턴스에 co-locate. 스케일 시 Qdrant/Milvus 등으로 외부화 |

> **#120 실배포 메모**: 설계 §9.1은 용량 제약 때문에 agentdb를 metadb **인스턴스**에 논리 DB로 co-locate하는 것을 v1로 두었으나, #119로 용량이 확보되어 **별도 `agentdb` 네임스페이스 + 전용 pgvector 인스턴스**로 분리 배포했다(metadb 이미지 교체 회피·서비스 경계 명확화). **빈 DB + pgvector 확장까지만 인프라가 프로비저닝**하고, 테이블 스키마(server-design §9.2 ERD: `agent_run`·`state_patch`·`run_event`·`approval_link`·`report_snapshot`)는 **앱 마이그레이션(FastAPI #134)이 소유**한다(인프라가 미리 만들지 않음). 매니페스트: `infra/k8s/agentdb/`.

FastAPI Agent는 Kubernetes/Kafka credential을 갖지 않는다. Spring Boot Operations Backend가 필요한 read/mutation 권한을 제한적으로 가진다.

> **#121 helm 차트·노출**: frontend·operations-backend·ai-service 모두 **`services/<svc>/helm/`** 에 차트 정비 완료(Harbor 이미지 `harbor.harbor.svc.cluster.local/library/bifrost-<svc>` + `imagePullSecrets: harbor-push-secret`, `bifrost-system` 배포). **외부 노출은 `bifrost-app` ALB 그룹 path 라우팅**: `/api`·`/ws` → operations-backend(order 10), `/` → frontend(order 20, catch-all). ai-service는 내부(ClusterIP)만. **실 이미지 빌드·배포는 CICD(#123)** — 차트만 준비. (operations-backend는 deploy 시 `jwt-secret`·`core-db-secret` 필요.)

#### 6.7 Observability

> 배치: 전부 `monitoring` namespace, **`app` 노드풀**(taint 없음)에 스케줄. DaemonSet(node-exporter·Promtail)은 data 풀 taint를 toleration 처리해 전 노드에 배치.
> **모니터링은 Spring Boot만이 아니라 클러스터 전체를 관측한다** — 노드·k8s 오브젝트·컨테이너·Kafka·DB·Spring·FastAPI·frontend가 모두 수집 대상이다.

| 리소스 | 권장 replica | 수집(scrape) 대상 |
| --- | --- | --- |
| Prometheus | 1 (HA 2) | node-exporter·kube-state-metrics·kubelet/cAdvisor·Spring `/actuator/prometheus`·FastAPI `/metrics`·kafka-exporter·JMX exporter·postgres-exporter |
| Alertmanager | 1 (HA 2) | Prometheus 룰 기반 alert routing |
| Grafana | 1 | Prometheus/Loki/Tempo 데이터소스 시각화 |
| Loki | 1 (single-binary) | 로그 저장 |
| Promtail/Alloy | **노드당 1 (DaemonSet)** | 전 노드의 pod stdout 수집 → Loki |
| node-exporter | **노드당 1 (DaemonSet)** | 노드 자원 metric |
| Tempo | 1 | distributed trace, connector task trace summary (앱이 OTLP 전송) |
| kafka-exporter | 1 | consumer lag/topic metric |
| JMX exporter | broker/connect sidecar | broker/connect JVM metric |
| kube-state-metrics | 1 | k8s 오브젝트 상태 metric |

→ 코어 단일 파드 6개(Prometheus·Alertmanager·Grafana·Loki·Tempo·kube-state-metrics) + DaemonSet 2종(노드당). Agent RCA(ai-service)를 위해 최소한 Prometheus·Loki·Tempo trace summary·Kafka lag metric은 필요하다.

### 7. Kafka 운영 구조 권장안

#### 7.1 현재 MVP 구조 유지

현 단계에서는 다음을 유지한다.

- Kafka cluster `platform-kafka`
- KafkaNodePool `kafka`
- replicas 3
- combined controller/broker
- gp3 50Gi PVC
- internal listeners

이 구조는 이미 정상 Running 상태이므로 폐기하지 않는다.

#### 7.2 즉시 보강할 설정

| 항목 | 권장 |
| --- | --- |
| topic 관리 | `KafkaTopic` CR로 관리 |
| service user | `KafkaUser` CR로 SCRAM user 생성 |
| auto topic create | bootstrap 후 false 검토 |
| listener | `scram` 표준화, plain 제한 |
| PDB | Kafka availability 보호 |
| anti-affinity | broker가 가능한 서로 다른 node에 뜨도록 설정 |
| Cruise Control | KafkaRebalance 사용 전 추가 |

#### 7.3 확장 구조

노드 여유가 생기면 다음으로 전환한다.

```text
KafkaNodePool controllers
  replicas: 3
  roles: [controller]
  storage: ephemeral or small persistent

KafkaNodePool brokers
  replicas: 3
  roles: [broker]
  storage: gp3 persistent claim
```

단, 현재 EKS 노드가 3개뿐이므로 controller/broker 분리는 capacity 검토 후 진행한다.

### 8. 진행 상태

#### 완료

- EKS 클러스터 사용 가능
- worker node **5개 Ready (단일 풀 t3.xlarge, #119)** — 구 3× t3.large 스펙업
- EBS CSI 설치
- gp3 StorageClass default
- Strimzi CRD 설치
- Strimzi Cluster Operator Running
- Kafka KRaft cluster Ready
- Kafka broker/controller 3개 Running
- Kafka broker PVC 3개 Bound
- 내부 KafkaTopic 3개 Ready
- metadb/**agentdb(pgvector pg16, #120)**/tenantdb workload Running
- `bifrost-system` ns + ai-service helm 차트·LLM secret·harbor-push-secret 준비 (#120, 실배포는 CICD #123 대기)
- Kafka Connect `platform-connect`(KafkaConnect CR, replicas 1) Ready — 2026-06-02 (수동)
- Harbor 8 pod Running — 2026-06-02 (수동, manifest 미반영)
- Jenkins `jenkins-0` Running — 2026-06-02 (수동)
- Argo CD 7 pod Running — 2026-06-02 (수동, Application 0개)

#### 수정 검토

- `auto.create.topics.enable: true` 운영 전 전환 검토
- `plain` listener 제거 또는 사용 범위 제한
- `tenantdb` LoadBalancer 노출 필요성 재검토
- Kafka PDB/anti-affinity 명시 여부 확인
- Kafka Connect plugin image를 Harbor 기반으로 재정의
- 기존 DB workload가 운영용인지 데모용인지 구분

#### 남은 작업

> Harbor·Jenkins·Argo CD·Kafka Connect 설치는 완료(수동). 아래는 그 이후 남은 작업이다.

1. **수동 배포 리소스(Harbor/Jenkins/Argo CD/Kafka Connect)의 manifest를 repo에 YAML로 역추출**
2. Argo CD Application 등록 + GitOps repository 구성(현재 App 0개)
3. Jenkins build → Harbor push → manifest tag update 파이프라인
4. Harbor imagePullSecret을 각 application namespace에 배포
5. Kafka Connect replicas 2 증설 + connector plugin image(Harbor) 재정의
6. KafkaUser / KafkaConnector / 추가 KafkaTopic 정의
7. Monitoring stack 설치(Prometheus/Grafana) + Loki/Tempo + exporter + metrics-server
8. Evidence Store / Audit Store / Metadata Store 구성
9. Cruise Control / KafkaRebalance 활성화
10. Spring Boot Operations Backend / FastAPI Agent / Frontend 배포(`bifrost-system`)
11. NetworkPolicy / RBAC / IngressClass 정리

### 9. 배포 순서

권장 순서:

1. 현재 Kafka 상태 백업 및 manifest Git 반영
2. Harbor 설치
3. Jenkins 설치 및 Harbor push 검증
4. Argo CD 설치
5. GitOps repository 구성
6. Monitoring stack 설치
7. Evidence Store / Audit Store / Metadata Store 구성
8. Kafka Connect custom image 빌드 및 배포
9. KafkaConnector CR 배포
10. Spring Boot Operations Backend 배포
11. FastAPI Agent 배포
12. Frontend 배포
13. Agent RCA replay와 운영 tool read-only 검증
14. approval 기반 mutation tool 제한 활성화

### 10. 문서 갱신 기준

다음 작업이 끝날 때마다 이 문서를 갱신한다.

- namespace 추가
- Kafka/Connect 구조 변경
- Harbor/Jenkins/Argo CD 설치
- Monitoring stack 설치
- application 배포
- LoadBalancer/Ingress 노출 변경
- 운영 정책상 금지/허용 리소스 변경

### 11. 클러스터 용량 분석 및 대응안 (2026-06-02) — ✅ 해소됨(#119)

> **해소(2026-06-05, #119)**: 아래 3×t3.large 포화 분석은 **해결됐다.** 대응안 (1)(2)(4)를 적용해 **단일 풀 5× t3.xlarge(allocatable ~19.5 vCPU)**로 스펙업 + right-size 완료. 현재 총 requests ~5.7 vCPU(~30%)로 모니터링·앱까지 여유. 현행 노드 스펙은 [§2.2 현재 클러스터 스냅샷](#2-현재-클러스터-스냅샷)이 단일 출처. 아래는 의사결정 근거로 보존한 **이력**이다.

> 기준: metrics-server가 없어 실시간 usage는 측정 불가하다. 아래는 **`kubectl describe node`의 requests/limits(스케줄링 기준)** 분석이다. 실제 right-sizing은 metrics-server 설치 후 usage로 보정한다.

#### 11.1 노드 스펙

| 항목 | 값 |
| --- | --- |
| 노드 | **3 × t3.large** (각 2 vCPU / 8GiB, **burstable**) |
| 노드당 allocatable | CPU **1930m**, MEM **~7.08GiB**, pods 35 |
| 클러스터 allocatable 합 | CPU **~5.79 vCPU**, MEM **~20.7GiB** |

#### 11.2 현재 할당 (requests / limits)

| 노드 | CPU req | MEM req | CPU limit | MEM limit | pods |
| --- | --- | --- | --- | --- | --- |
| 128-71 | 1381m (71%) | 3561Mi (50%) | 5010m (259%) | 6864Mi (96%) | 11 |
| 131-173 | 1491m (77%) | 3921Mi (55%) | 5510m (285%) | 9328Mi (131%) | 14 |
| 159-30 | 1791m (**92%**) | 4317Mi (60%) | 5510m (285%) | 9668Mi (**136%**) | 18 |
| **합계** | **~4663m (≈81%)** | **~11.5GiB (≈56%)** | 과다 오버커밋 | **~25.3GiB (≈122%)** | 43 |

#### 11.3 진단

- **CPU가 병목이다.** 요청이 이미 클러스터 ≈81%, 노드3은 92%로 **여유 CPU가 클러스터 전체 ~1.1 vCPU(노드3은 ~0.14 vCPU)뿐**이다. 추가 워크로드는 곧 `Pending`(unschedulable)으로 떨어진다.
- **메모리 limit 오버커밋.** 노드2·3의 메모리 limit이 allocatable을 초과(131%·136%, 클러스터 122%). 메모리는 압축 불가 자원이라 동시 burst 시 **OOMKill/eviction 위험**이 있다(요청 기준 여유는 ~9GiB로 아직 있음).
- **t3는 burstable.** baseline은 vCPU당 30%(노드당 지속 ~0.6 vCPU). Kafka broker·Kafka Connect·JVM·DB 같은 **지속 부하**가 CPU 크레딧을 소진하면 throttling이 발생한다 — 데이터/상태 플레인에 t3는 부적합.
- **아직 안 뜬 스택이 더 크다.** 남은 필수 구성(`monitoring`: Prometheus+Loki+Tempo+Grafana+Promtail DS+exporter, `bifrost-system`: Spring JVM+FastAPI+Frontend, Kafka Connect replica 2, Evidence/Audit Store, metrics-server)의 요청 합은 대략 **+3~6 vCPU / +6~12GiB**로 추정된다. 현재 가용(CPU ~1.1 vCPU)으로는 **수용 불가**.

**결론: 현재 3×t3.large로는 설계상 남은 컴포넌트를 올릴 수 없다. 노드 증설 또는 인스턴스 상향이 선행돼야 한다.**

#### 11.4 대응안 (우선순위)

> 단일 EKS·VPC 제약([§1.2](#2-제약사항))은 **새 클러스터/VPC 생성** 금지일 뿐, 기존 노드그룹의 수량·인스턴스 타입 조정은 허용된다.

| # | 대응 | 내용 | 효과/비고 |
| --- | --- | --- | --- |
| 1 | **인스턴스 상향(권장)** | 데이터/상태·관측 플레인용으로 **m5/m6i.xlarge(4 vCPU/16GiB)** 3~4대로 전환 또는 혼합 | burstable 탈피(지속 부하 안정) + 용량 확보. Kafka/JVM에 적합 |
| 2 | 노드 증설 | nodegroup desired 3 → 5~6(t3.large) | 가장 빠르지만 t3 크레딧 문제는 잔존 → 임시방편 |
| 3 | 옵셔널 축소 | Harbor `trivy` off, monitoring 리텐션·리소스 축소, Cruise Control 보류, 비핵심 단일 replica 유지 | 즉시 수 백 m~1 vCPU 절감 |
| 4 | requests/limits 정합 | 메모리 limit ≤ allocatable로 캡, 핵심 stateful(Kafka·DB)은 requests=limits(Guaranteed) | eviction/OOM 위험 제거 |
| 5 | 전용 노드그룹/taint | Kafka(stateful·지속) 전용 노드 분리, 관측/CI는 별도 노드그룹 | 자원 경합·장애 격리 |
| 6 | metrics-server 설치 | 실제 usage 기반 right-sizing·HPA 가능 | 현재는 requests 추정만 가능 |

**권장 경로**: (4)(3)로 당장의 위험을 낮추고 metrics-server(6)로 실측한 뒤, 남은 스택 배포 전에 (1) 인스턴스 상향(예: 3×m5.xlarge = 12 vCPU/48GiB)을 적용한다.
