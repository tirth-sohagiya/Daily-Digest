data "aws_iam_policy_document" "lambda_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "digest" {
  name               = "${var.project_name}-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_trust.json
}

data "aws_iam_policy_document" "digest" {
  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.digest.arn}:*"]
  }

  statement {
    sid       = "ReadWriteSendSentinel"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem"]
    resources = [aws_dynamodb_table.digest.arn]
  }

  statement {
    sid       = "SendDigest"
    actions   = ["ses:SendEmail"]
    resources = [aws_sesv2_email_identity.sender.arn]
  }

  statement {
    sid     = "ReadEstimatedCharges"
    actions = ["cloudwatch:GetMetricStatistics"]

    # CloudWatch metric reads support no resource-level permissions; the wildcard is
    # unavoidable rather than lazy, and this is the only one in the policy.
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "digest" {
  name   = "${var.project_name}-lambda"
  role   = aws_iam_role.digest.id
  policy = data.aws_iam_policy_document.digest.json
}
