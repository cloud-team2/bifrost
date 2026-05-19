#!/usr/bin/env bash
set -euo pipefail

CLUSTER="skala3-cloud-team-Curly"
NODEGROUP="${CLUSTER}-ng-main"
PROFILE="skala_student"
VERSIONS=("1.31" "1.32" "1.33" "1.34" "1.35")

wait_cluster_active() {
  local ver=$1
  echo "  컨트롤 플레인 ${ver} 완료 대기 중..."
  while true; do
    STATUS=$(AWS_PROFILE=${PROFILE} aws eks describe-cluster \
      --name "${CLUSTER}" \
      --query 'cluster.status' --output text 2>/dev/null)
    VER=$(AWS_PROFILE=${PROFILE} aws eks describe-cluster \
      --name "${CLUSTER}" \
      --query 'cluster.version' --output text 2>/dev/null)
    if [[ "${STATUS}" == "ACTIVE" && "${VER}" == "${ver}" ]]; then
      echo "  컨트롤 플레인 ${ver} ACTIVE"
      break
    fi
    sleep 30
  done
}

wait_nodegroup_active() {
  local ver=$1
  echo "  노드 그룹 ${ver} 완료 대기 중..."
  while true; do
    STATUS=$(AWS_PROFILE=${PROFILE} aws eks describe-nodegroup \
      --cluster-name "${CLUSTER}" \
      --nodegroup-name "${NODEGROUP}" \
      --query 'nodegroup.status' --output text 2>/dev/null)
    NG_VER=$(AWS_PROFILE=${PROFILE} aws eks describe-nodegroup \
      --cluster-name "${CLUSTER}" \
      --nodegroup-name "${NODEGROUP}" \
      --query 'nodegroup.version' --output text 2>/dev/null)
    if [[ "${STATUS}" == "ACTIVE" && "${NG_VER}" == "${ver}" ]]; then
      echo "  노드 그룹 ${ver} ACTIVE"
      break
    fi
    sleep 30
  done
}

echo "[EKS 순차 업그레이드] 시작: $(date)"

CURRENT=$(AWS_PROFILE=${PROFILE} aws eks describe-cluster \
  --name "${CLUSTER}" \
  --query 'cluster.version' --output text)
echo "현재 버전: ${CURRENT}"

for VER in "${VERSIONS[@]}"; do
  CURRENT=$(AWS_PROFILE=${PROFILE} aws eks describe-cluster \
    --name "${CLUSTER}" \
    --query 'cluster.version' --output text)

  if [[ "${CURRENT}" == "${VER}" ]]; then
    echo "[${VER}] 컨트롤 플레인 이미 ${VER} — 노드 그룹 확인 중..."
  else
    CLUSTER_STATUS=$(AWS_PROFILE=${PROFILE} aws eks describe-cluster \
      --name "${CLUSTER}" --query 'cluster.status' --output text)
    if [[ "${CLUSTER_STATUS}" == "UPDATING" ]]; then
      echo "[${VER}] 컨트롤 플레인 업데이트 이미 진행 중 — 대기..."
      wait_cluster_active "${VER}"
    else
      echo "[${VER}] 컨트롤 플레인 업그레이드 시작: $(date)"
      AWS_PROFILE=${PROFILE} aws eks update-cluster-version \
        --name "${CLUSTER}" \
        --kubernetes-version "${VER}" \
        --output json > /dev/null
      wait_cluster_active "${VER}"
    fi
  fi

  NG_VER=$(AWS_PROFILE=${PROFILE} aws eks describe-nodegroup \
    --cluster-name "${CLUSTER}" \
    --nodegroup-name "${NODEGROUP}" \
    --query 'nodegroup.version' --output text)
  NG_STATUS=$(AWS_PROFILE=${PROFILE} aws eks describe-nodegroup \
    --cluster-name "${CLUSTER}" \
    --nodegroup-name "${NODEGROUP}" \
    --query 'nodegroup.status' --output text)

  if [[ "${NG_VER}" == "${VER}" && "${NG_STATUS}" == "ACTIVE" ]]; then
    echo "[${VER}] 노드 그룹 이미 ${VER}"
  else
    echo "[${VER}] 노드 그룹 업그레이드 시작: $(date)"
    AWS_PROFILE=${PROFILE} aws eks update-nodegroup-version \
      --cluster-name "${CLUSTER}" \
      --nodegroup-name "${NODEGROUP}" \
      --kubernetes-version "${VER}" \
      --output json > /dev/null
    wait_nodegroup_active "${VER}"
  fi

  echo "[${VER}] 완료: $(date)"
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "EKS 1.35 업그레이드 완료: $(date)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
