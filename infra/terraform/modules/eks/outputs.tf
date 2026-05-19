output "cluster_name" {
  description = "EKS 클러스터 이름"
  value       = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  description = "EKS API 서버 엔드포인트"
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_ca_data" {
  description = "EKS 클러스터 CA 인증서 (base64)"
  value       = aws_eks_cluster.main.certificate_authority[0].data
  sensitive   = true
}

output "cluster_version" {
  description = "Kubernetes 버전"
  value       = aws_eks_cluster.main.version
}

output "oidc_provider_arn" {
  description = "IRSA용 OIDC Provider ARN"
  value       = aws_iam_openid_connect_provider.eks.arn
}

output "oidc_provider_url" {
  description = "OIDC Provider URL (https:// 제외)"
  value       = replace(aws_iam_openid_connect_provider.eks.url, "https://", "")
}

output "node_group_role_arn" {
  description = "노드 그룹 IAM Role ARN"
  value       = aws_iam_role.nodes.arn
}

output "cluster_security_group_id" {
  description = "클러스터 보안 그룹 ID"
  value       = aws_security_group.cluster.id
}

output "nodes_security_group_id" {
  description = "노드 보안 그룹 ID"
  value       = aws_security_group.nodes.id
}

output "kubeconfig_command" {
  description = "kubeconfig 업데이트 명령어"
  value       = "aws eks update-kubeconfig --region ap-northeast-2 --name ${var.cluster_name} --profile skala_student"
}
