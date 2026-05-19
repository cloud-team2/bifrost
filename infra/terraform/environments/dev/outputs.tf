output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "oidc_provider_arn" {
  value = module.eks.oidc_provider_arn
}

output "kubeconfig_command" {
  value = module.eks.kubeconfig_command
}

output "docker_hub_images" {
  description = "Docker Hub 이미지 주소 목록"
  value = {
    core_service          = "hwnnn/bifrost-core-service:latest"
    orchestrator_service  = "hwnnn/bifrost-orchestrator-service:latest"
    ai_service            = "hwnnn/bifrost-ai-service:latest"
    kafka_connect         = "hwnnn/bifrost-kafka-connect:latest"
  }
}
