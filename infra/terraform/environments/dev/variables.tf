variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "cluster_name" {
  type    = string
  default = "skala3-cloud1-finalproj-team2"
}

variable "kubernetes_version" {
  type    = string
  default = "1.35"
}

variable "vpc_id" {
  description = "기존 VPC ID"
  type        = string
}

variable "private_subnet_ids" {
  description = "EKS 노드용 프라이빗 서브넷 ID 목록"
  type        = list(string)
}

variable "public_subnet_ids" {
  description = "ALB용 퍼블릭 서브넷 ID 목록"
  type        = list(string)
}

variable "node_groups" {
  description = "티어별 EKS 노드풀 (app/data 등)"
  type = map(object({
    instance_types = list(string)
    desired_size   = number
    min_size       = number
    max_size       = number
    labels         = optional(map(string), {})
    taints = optional(list(object({
      key    = string
      value  = string
      effect = string
    })), [])
  }))
}
