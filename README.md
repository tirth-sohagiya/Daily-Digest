# Daily Digest

A serverless morning email. Every day at 7:00 AM Pacific, a Java function on AWS Lambda
gathers weather, calendar events, and GitHub notifications, renders one email, sends it
through SES, and shuts down.

No server, no website, nothing to log into. Provisioned entirely with Terraform, and runs
for about **$0.05/month** — the only line item outside the free tier is the Cost Explorer
API.

```
Subject: Morning — Thu Aug 27

WEATHER
  62°F / 89°F, overcast
  40% chance of rain today

TODAY
  10:00  Standup
  14:30  Dentist

GITHUB
  3 unread notifications
  · review requested — acme/api-server #402
```

**Stack** — Java 21 on Lambda (arm64), Terraform, EventBridge Scheduler, SES v2, DynamoDB,
SSM Parameter Store, CloudWatch, SNS.

## Status

The delivery pipeline is being built end to end with a single source before the rest are
added, so that a failure has one plausible cause instead of six.

Working: the `Source`/`Section` abstraction and the weather source, verified against the
live Open-Meteo API. In progress: Terraform state bootstrap, then Lambda, IAM, scheduler,
and SES delivery. Planned: calendar, GitHub, and AWS spend sources.

## Architecture

```
EventBridge Scheduler (cron, America/Los_Angeles)
        │
        ▼
   Lambda (Java 21, arm64)
        │
        ├──► Open-Meteo / Google iCal / GitHub API   (outbound HTTPS)
        ├──► SSM Parameter Store    (secrets)
        ├──► DynamoDB               (send idempotency)
        └──► SES v2                 (deliver)
        │
        ▼
  CloudWatch Logs ──► Alarm ──► SNS ──► phone
```

Each section of the email comes from a class implementing a single-method `Source`
interface. Adding a source means writing one class and appending it to a list.

## Design decisions

**No VPC.** The function makes only outbound HTTPS calls, so private networking buys it
nothing — but it would require a NAT Gateway at roughly $32/month, several hundred times
the rest of the bill. This is the most common source of surprise AWS bills on small
projects, and avoiding it is deliberate.

**EventBridge Scheduler, not EventBridge Rules.** Rules evaluate cron in UTC only, so a
7:00 AM local digest silently arrives at 8:00 for half the year. Scheduler takes an IANA
timezone and handles daylight saving itself; hand-computing an offset is the same bug
written by hand.

**A partial digest beats no digest.** Every source is invoked in isolation. If one throws
or times out, the email still sends with the remaining sections and a one-line note where
the broken one would have been — a failure that arrives in the inbox is a failure someone
notices.

**The idempotency sentinel is written after SES succeeds, never before.** Lambda retries
failed invocations, so the handler exits early if it finds a `DIGEST#<date>` marker in
DynamoDB. Writing that marker first would turn a transient SES error into a silently
skipped day.

**Alarms fire on absence, not just errors.** A cron job that stops running produces no
errors — it produces nothing, which goes unnoticed for weeks. CloudWatch alarms cover both
`Errors >= 1` and the lack of a successful invocation in 36 hours.

**The AWS spend section runs Mondays only.** `GetCostAndUsage` costs $0.01 per request with
no free tier, so daily calls would be $0.30/month — most of the project's total spend — to
re-read a number that barely moves. Weekly gives the same signal for $0.04, and the section
is behind a Terraform variable that turns it off entirely.

## Infrastructure notes

Terraform is split into two applies. `bootstrap/` creates the S3 bucket that holds remote
state and is applied once with local state, which is committed — a bucket cannot be managed
by state stored inside the bucket it creates. Everything else uses the remote backend.

The Lambda execution role is scoped to exactly what it needs: `ses:SendEmail` on one
verified identity, DynamoDB `GetItem`/`PutItem` on one table, `ssm:GetParameter` on one
path prefix, and logs on its own group. The single wildcard is `ce:GetCostAndUsage` on
`*`, which the Cost Explorer API requires because it supports no resource-level
permissions.

Secrets — the Google Calendar iCal URL and the GitHub token — live in SSM as `SecureString`
parameters and are read at runtime. Neither appears in Terraform state or in this
repository.

## Running locally

Requires JDK 21 or newer and Maven 3.9+.

```bash
mvn -q compile exec:java
```

Fetches from the live source APIs and prints the digest to the terminal. Touches no AWS
services, so it needs no credentials.
