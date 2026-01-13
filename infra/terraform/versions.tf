terraform {
  required_version = ">= 1.10, < 2.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

provider "aws" {
  region = var.region

  # Every resource carries Project=hotshield, which is what the after-destroy check in README.md searches for.
  default_tags {
    tags = {
      Project = "hotshield"
      Run     = var.run
    }
  }
}
