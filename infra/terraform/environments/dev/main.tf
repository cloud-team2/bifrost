terraform {
  required_version = ">= 1.5.0"
  
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  
  # Production에서는 S3 backend 사용 권장
  # backend "s3" {
  #   bucket = "platform-terraform-state"
  #   key    = "dev/terraform.tfstate"
  #   region = "ap-northeast-2"
  # }
}

provider "aws" {
  region = var.aws_region
  
  default_tags {
    tags = {
      Environment = "dev"
      ManagedBy   = "Terraform"
      Project     = "data-orchestration-platform"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

# TODO: VPC, EKS, ECR 모듈 호출
# module "vpc" {
#   source = "../../modules/vpc"
#   ...
# }
#
# module "eks" {
#   source = "../../modules/eks"
#   ...
# }
