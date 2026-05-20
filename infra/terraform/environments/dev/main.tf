terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # S3 backend — 팀 공유 상태 파일 (bucket/key는 팀 합의 후 설정)
  # backend "s3" {
  #   bucket  = "bifrost-terraform-state"
  #   key     = "dev/terraform.tfstate"
  #   region  = "ap-northeast-2"
  #   profile = "skala_student"
  # }
}

provider "aws" {
  region  = var.aws_region
  profile = "skala_student"

  default_tags {
    tags = local.common_tags
  }
}

locals {
  common_tags = {
    Project     = "bifrost"
    Team        = "Curly"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}

################################################################################
# EKS
################################################################################

module "eks" {
  source = "../../modules/eks"

  cluster_name       = var.cluster_name
  kubernetes_version = var.kubernetes_version
  vpc_id             = var.vpc_id
  private_subnet_ids = var.private_subnet_ids
  public_subnet_ids  = var.public_subnet_ids

  node_instance_type = var.node_instance_type
  node_desired_size  = var.node_desired_size
  node_min_size      = var.node_min_size
  node_max_size      = var.node_max_size
  node_disk_size_gb  = 50

  cluster_admin_arns = [
    "arn:aws:iam::881490135253:user/skala-student",
    "arn:aws:iam::881490135253:user/skala_dev",
  ]

  tags = local.common_tags
}

# 컨테이너 레지스트리: Docker Hub (hwnnn/bifrost-*)
# ECR 모듈은 사용하지 않음
