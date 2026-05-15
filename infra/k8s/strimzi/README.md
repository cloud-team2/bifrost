# Strimzi Operator 설치

Strimzi는 Kafka, KafkaConnect, KafkaTopic, KafkaUser, KafkaConnector를 K8s native하게 관리하는 Operator.

## 설치 (간단)

```bash
# Strimzi Operator namespace
kubectl create namespace strimzi-system

# Operator 설치 (모든 namespace watch)
kubectl apply -f https://strimzi.io/install/latest?namespace=strimzi-system \
  -n strimzi-system

# 확인
kubectl get pods -n strimzi-system
```

## 설치 (Helm)

```bash
helm repo add strimzi https://strimzi.io/charts/
helm repo update
helm install strimzi-operator strimzi/strimzi-kafka-operator \
  --namespace strimzi-system --create-namespace \
  --set watchAnyNamespace=true
```

## 다음 단계

- `../kafka/kafka-cluster.yaml` — Kafka 클러스터 생성
- `../kafka/kafka-connect.yaml` — Kafka Connect 클러스터
