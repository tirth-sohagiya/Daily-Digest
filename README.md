# Daily Digest

A serverless morning email. Every day at 7:00 AM Pacific, a Java function on AWS Lambda
gathers weather, immigration policy news, new-grad job postings, and a visa countdown,
renders one email, sends it through SES, and shuts down.

No server, no website, nothing to log into. Nineteen AWS resources, all provisioned with
Terraform, running for **$0.00/month** — every API it touches is either free-tier or free
outright.

```
WEATHER — THU SEP 3
  57°F / 80°F, overcast
  2% chance of rain

OPT STATUS
  73 of 90 unemployment days remaining · through Nov 15

NEW GRAD JOBS — 16 new
  H-1B · Applied Intuition — Forward Deployed Engineer New Grad — Sunnyvale, CA
  Amazon — Applied Scientist, Global Risk Intelligence — Seattle, WA
  … 14 more

IMMIGRATION NEWS
  Forbes · DHS Adds Immigration Rule To Agenda That Stops H-1B Spouses From Working
  Boundless Immigration · Proposal to End 60-Day Grace Period Clears Federal Review

AWS SPEND
  $0.00 month to date
```

Sent as `multipart/alternative`, so headlines and job postings are clickable in HTML
clients and still readable as plain text everywhere else.

**Stack** — Java 21 on Lambda (arm64), Terraform, EventBridge Scheduler, DynamoDB,
SES v2, CloudWatch Logs and Alarms, SNS, S3.

## Status

Running unattended since 31 August 2026. Nine sources, each an implementation of a
single-method `Source` interface:

| Source | Data |
|---|---|
| `WeatherSource` | Open-Meteo forecast, UV/wind/feels-like advisories |
| `AlertSource` | National Weather Service active alerts |
| `OptStatusSource` | Visa unemployment countdown, freezes when employment starts |
| `DeadlineSource` | Dated reminders from a Terraform map |
| `JobSource` | New-grad postings, filtered by category and city, H-1B sponsors flagged |
| `ImmigrationSource` | Federal Register rulemaking |
| `NewsSource` | Immigration news, ranked and deduplicated across days |
| `SpendSource` | Month-to-date AWS charges |
| `QuoteSource` | A bundled quote, indexed by day of year |

Adding a source means writing one class and appending one line to a list.

## Architecture

```
EventBridge Scheduler (cron, America/Los_Angeles)
        │
        ▼
   Lambda (Java 21, arm64, 512 MB)
        │
        ├──► Open-Meteo · weather.gov · Federal Register     (outbound HTTPS)
        ├──► Google News · Economic Times · GitHub raw
        ├──► CloudWatch    (billing metric)
        ├──► DynamoDB      (idempotency, dedupe, watermarks)
        └──► SES v2        (deliver)
        │
        ▼
  CloudWatch Logs ──► 3 alarms ──► SNS ──► email
```

## Design decisions

**No VPC.** The function makes only outbound HTTPS calls, so private networking buys it
nothing — but it would require a NAT Gateway at roughly $32/month, several hundred times
the rest of the bill. This is the most common source of surprise AWS bills on small
projects, and avoiding it is deliberate.

**EventBridge Scheduler, not EventBridge Rules.** Rules evaluate cron in UTC only, so a
7:00 AM local digest silently arrives at 8:00 for half the year. Scheduler takes an IANA
timezone and handles daylight saving itself; hand-computing an offset is the same bug
written by hand.

**A free metric instead of the paid API.** The AWS spend section originally called Cost
Explorer's `GetCostAndUsage`, at $0.01 per request with no free tier — $0.30/month, which
would have been roughly 87% of the project's entire bill. CloudWatch publishes
`EstimatedCharges` for free and answers the same question, which is what took the running
cost from $0.05 to $0.00.

**Streaming a 13 MB feed inside a 512 MB function.** The job listings file holds ~19,000
postings, and parsing it the usual way materializes all of them at once. Reading it one
element at a time with a streaming parser holds peak memory to **213 MB**, measured from
Lambda's own `Max Memory Used` — four megabytes above the same function without it.

**Deduplicating stories, not strings.** The same news story is republished for days under
different headlines. Matching exact text catches none of that, so headlines are reduced to
sets of significant words and compared by overlap against the last forty shown. Job
postings instead use a timestamp watermark, because a posting has one reliable date and
appears once — different data, different memory.

**A partial digest beats no digest.** Every source is invoked in isolation. If one throws
or times out, the email still sends with the remaining sections — and a failure that
arrives in the inbox is a failure someone notices.

**The idempotency sentinel is written after SES succeeds, never before.** Lambda retries
failed invocations, so the handler exits early if it finds a `DIGEST#<date>` marker in
DynamoDB. Writing that marker first would turn a transient SES error into a silently
skipped day.

**Alarms fire on absence, not just errors.** A cron job that stops running produces no
errors — it produces nothing, which goes unnoticed for weeks. One alarm covers
`Errors >= 1`; another covers the *lack* of a successful invocation in 36 hours, with
`treat_missing_data = "breaching"`, since absence of data is precisely the condition it
exists to catch.

## Infrastructure notes

Terraform is split into two applies. `bootstrap/` creates the S3 bucket that holds remote
state, because a bucket cannot be managed by state stored inside the bucket it creates.
Everything else lives in `infra/` and uses that bucket as its backend, with S3-native
locking rather than the DynamoDB lock table deprecated in Terraform 1.11.

Deploying your own copy means running `bootstrap/` once in your own AWS account, which
produces a bucket with its own random suffix. That workspace's state is deliberately not
committed: it describes one specific account's bucket, and is useless — actively
misleading — to anyone deploying elsewhere. Terraform code is shared; Terraform state is
not.

The Lambda execution role is scoped to exactly what it needs: `ses:SendEmail` on one
verified identity, DynamoDB `GetItem`/`PutItem` on one table, and logs on its own group.
The single wildcard is `cloudwatch:GetMetricStatistics` on `*`, which the API requires
because it supports no resource-level permissions — commented in place, since a reviewer
will look for exactly that.

There are no secrets. Every feed the digest reads is public, so SSM Parameter Store was
scoped out rather than added for its own sake. Personal configuration — email address,
visa dates, job filters — lives in `terraform.tfvars`, which is gitignored;
`example.tfvars` documents what to supply.

## Running locally

Requires JDK 21 or newer and Maven 3.9+.

```bash
mvn -q compile exec:java
```

Prints the digest to the terminal, sending no email. It runs the sources that need no
persistence — weather, alerts, AWS spend, and the quote. Jobs, news, and Federal Register
items depend on DynamoDB for deduplication, so those are exercised by deploying and
invoking the function.
