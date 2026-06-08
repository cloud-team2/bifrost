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

variable "node_groups" {
  description = "티어별 EKS 관리형 노드풀 정의 (키 = 풀 이름, 노드그룹명은 <cluster>-ng-<key>)"
  type = map(object({
    instance_types = list(string)
    desired_size   = number
    min_size       = number
    max_size       = number
    labels         = optional(map(string), {})
    taints = optional(list(object({
      key    = string
      value  = string
      effect = string # NO_SCHEDULE | NO_EXECUTE | PREFER_NO_SCHEDULE
    })), [])
  }))
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
