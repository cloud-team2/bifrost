variable "cluster_name" {
  description = "EKS 클러스터 이름"
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes 버전"
  type        = string
  default     = "1.35"
}

variable "vpc_id" {
  description = "기존 VPC ID"
  type        = string
}

variable "private_subnet_ids" {
  description = "EKS 노드가 위치할 프라이빗 서브넷 ID 목록"
  type        = list(string)
}

variable "public_subnet_ids" {
  description = "ALB/NAT용 퍼블릭 서브넷 ID 목록"
  type        = list(string)
}

variable "node_instance_type" {
  description = "EKS 워커 노드 EC2 인스턴스 타입"
  type        = string
  default     = "t3.large"
}

variable "node_desired_size" {
  description = "워커 노드 Desired 수"
  type        = number
  default     = 3
}

variable "node_min_size" {
  description = "워커 노드 최소 수"
  type        = number
  default     = 2
}

variable "node_max_size" {
  description = "워커 노드 최대 수"
  type        = number
  default     = 5
}

variable "node_disk_size_gb" {
  description = "워커 노드 루트 볼륨 크기 (GiB)"
  type        = number
  default     = 50
}

variable "cluster_admin_arns" {
  description = "EKS ClusterAdmin 권한을 부여할 IAM User/Role ARN 목록"
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "리소스에 공통으로 붙일 태그"
  type        = map(string)
  default     = {}
}
