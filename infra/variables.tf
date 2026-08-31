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

variable "latitude" {
  description = "Forecast latitude."
  type        = string
  default     = "37.3382"
}

variable "longitude" {
  description = "Forecast longitude."
  type        = string
  default     = "-121.8863"
}

variable "timezone" {
  description = "IANA timezone for the forecast and the delivery schedule."
  type        = string
  default     = "America/Los_Angeles"
}

variable "sender_email" {
  description = "Verified SES identity the digest is sent from. Set in terraform.tfvars; never committed."
  type        = string
}

variable "recipient_email" {
  description = "Where the digest is delivered. Must also be verified while SES is in sandbox mode."
  type        = string
}
