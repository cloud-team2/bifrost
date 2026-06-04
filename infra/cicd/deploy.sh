#!/usr/bin/env bash
# CICD 스택 배포 스크립트 (Jenkins / ArgoCD / Harbor + ALB Ingress)
# 사용법: ./infra/cicd/deploy.sh [all|jenkins|argocd|harbor|ingress]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ── 공통 설정 ──────────────────────────────────────────────────────────────────
CHART_JENKINS_VER="${CHART_JENKINS_VER:-5.9.22}"
CHART_ARGOCD_VER="${CHART_ARGOCD_VER:-9.5.17}"
CHART_HARBOR_VER="${CHART_HARBOR_VER:-1.18.0}"

# Helm 레포 등록 ────────────────────────────────────────────────────────────────
add_repos() {
  helm repo add jenkins  https://charts.jenkins.io           2>/dev/null || true
  helm repo add argo     https://argoproj.github.io/argo-helm 2>/dev/null || true
  helm repo add harbor   https://helm.goharbor.io            2>/dev/null || true
  helm repo update jenkins argo harbor
}

# Jenkins ───────────────────────────────────────────────────────────────────────
deploy_jenkins() {
  echo "[jenkins] 배포 중..."
  kubectl create namespace jenkins --dry-run=client -o yaml | kubectl apply -f -
  helm upgrade --install jenkins jenkins/jenkins \
    -n jenkins \
    -f "$SCRIPT_DIR/jenkins-values.yaml" \
    --version "$CHART_JENKINS_VER" \
    --wait --timeout 5m
  echo "[jenkins] 완료"
}

# ArgoCD ────────────────────────────────────────────────────────────────────────
deploy_argocd() {
  echo "[argocd] 배포 중..."
  kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
  helm upgrade --install argocd argo/argo-cd \
    -n argocd \
    -f "$SCRIPT_DIR/argocd-values.yaml" \
    --version "$CHART_ARGOCD_VER" \
    --wait --timeout 5m
  echo "[argocd] 완료"
}

# Harbor ────────────────────────────────────────────────────────────────────────
deploy_harbor() {
  echo "[harbor] 배포 중..."
  kubectl create namespace harbor --dry-run=client -o yaml | kubectl apply -f -
  helm upgrade --install harbor harbor/harbor \
    -n harbor \
    -f "$SCRIPT_DIR/harbor-values.yaml" \
    --version "$CHART_HARBOR_VER" \
    --wait --timeout 10m
  echo "[harbor] 완료"
}

# ALB Ingress ───────────────────────────────────────────────────────────────────
deploy_ingress() {
  echo "[ingress] ALB Ingress 적용 중..."
  kubectl apply -f "$REPO_ROOT/infra/k8s/ingress/harbor-ingress.yaml"
  kubectl apply -f "$REPO_ROOT/infra/k8s/ingress/jenkins-ingress.yaml"
  kubectl apply -f "$REPO_ROOT/infra/k8s/ingress/argocd-ingress.yaml"
  echo "[ingress] 완료"
  echo ""
  echo "ALB DNS:"
  kubectl get ingress -n harbor harbor-alb \
    -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null && echo
}

# ── main ───────────────────────────────────────────────────────────────────────
TARGET="${1:-all}"

add_repos

case "$TARGET" in
  all)
    deploy_jenkins
    deploy_argocd
    deploy_harbor
    deploy_ingress
    ;;
  jenkins) deploy_jenkins ;;
  argocd)  deploy_argocd  ;;
  harbor)  deploy_harbor  ;;
  ingress) deploy_ingress ;;
  *)
    echo "사용법: $0 [all|jenkins|argocd|harbor|ingress]"
    exit 1
    ;;
esac

echo ""
echo "=== 배포 상태 확인 ==="
kubectl get pods -n jenkins  --no-headers 2>/dev/null | awk '{print "jenkins  " $1 " " $3}'
kubectl get pods -n argocd   --no-headers 2>/dev/null | awk '{print "argocd   " $1 " " $3}'
kubectl get pods -n harbor   --no-headers 2>/dev/null | awk '{print "harbor   " $1 " " $3}'
