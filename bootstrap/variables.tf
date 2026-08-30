variable "aws_region" {
  description = "Region for all Daily Digest resources."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix for resource names."
  type        = string
  default     = "daily-digest"
}
