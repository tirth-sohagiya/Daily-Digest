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

variable "schedule_expression" {
  description = "When the digest is sent, in AWS six-field cron syntax."
  type        = string
  default     = "cron(0 7 * * ? *)"
}

variable "cost_alarm_threshold_usd" {
  description = "Estimated month-to-date charges that trigger the billing alarm."
  type        = number
  default     = 1
}
