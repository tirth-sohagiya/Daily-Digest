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

variable "deadlines" {
  description = "Label to ISO-8601 date. Personal, so real values live in terraform.tfvars."
  type        = map(string)
  default     = {}
}

variable "opt_start_date" {
  description = "OPT start date (ISO-8601). Personal, so the real value lives in terraform.tfvars."
  type        = string
  default     = ""
}

variable "employment_start_date" {
  description = "Set once employed; freezes the unemployment tally. Empty means still unemployed."
  type        = string
  default     = ""
}

variable "opt_unemployment_days" {
  description = "Aggregate unemployment days allowed. 90 on post-completion OPT, 150 with STEM OPT."
  type        = number
  default     = 90
}
