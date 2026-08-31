resource "aws_cloudwatch_log_group" "digest" {
  name              = "/aws/lambda/${var.project_name}"
  retention_in_days = 14
}
