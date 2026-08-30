output "state_bucket_name" {
  description = "Bucket name to paste into infra/backend.tf."
  value       = aws_s3_bucket.terraform_state.bucket
}

output "state_bucket_region" {
  description = "Region to paste into infra/backend.tf."
  value       = var.aws_region
}
