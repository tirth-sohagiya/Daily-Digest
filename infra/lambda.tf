locals {
  jar_path = "${path.module}/../target/daily-digest-1.0.0.jar"
}

resource "aws_lambda_function" "digest" {
  function_name = var.project_name
  role          = aws_iam_role.digest.arn
  handler       = "com.tirth.digest.Handler::handleRequest"
  runtime       = "java21"
  architectures = ["arm64"]

  filename         = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)

  timeout     = 60
  memory_size = 512

  environment {
    variables = {
      LATITUDE        = var.latitude
      LONGITUDE       = var.longitude
      TIMEZONE        = var.timezone
      SENDER_EMAIL    = var.sender_email
      RECIPIENT_EMAIL = var.recipient_email
    }
  }

  depends_on = [aws_cloudwatch_log_group.digest]
}
