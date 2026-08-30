terraform {
  backend "s3" {
    bucket       = "daily-digest-tfstate-73376b2f"
    key          = "infra/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
