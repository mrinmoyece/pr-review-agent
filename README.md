# PR Review Agent 🔍

> Enterprise-grade AI PR review agent for any language or stack. Multi-agent parallel review (security, architecture, performance, testing) via GitHub webhooks — posts structured reports to any PR.

[![CI](https://github.com/mrinmoyece/pr-review-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/mrinmoyece/pr-review-agent/actions)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![OWASP](https://img.shields.io/badge/OWASP-Top%2010-red)](https://owasp.org/www-project-top-ten/)

## What It Does

The agent listens for signed GitHub `pull_request` webhook events and runs
deterministic checks followed by parallel specialist review rounds and an
adversarial verification round.

| Dimension | What It Checks |
|-----------|---------------|
| 🔒 Security | OWASP Top 10: SQL injection, weak crypto, hardcoded secrets, missing auth, unvalidated input |
| 🏗️ Architecture | RestTemplate deprecation, missing circuit breakers, controller→repo coupling, @Transactional misuse |
| ⚡ Performance | N+1 queries, unnecessary synchronisation, missing pagination, blocking I/O in reactive chains |
| 🧠 Specialist review | Security, correctness, tests, architecture, performance, and operations |
| 🔎 Verification | Challenges earlier findings and searches for high-impact misses |

## Architecture

See [Architecture](docs/architecture.md) for system context, trust boundaries,
component responsibilities, state, concurrency, failure semantics, and
deployment topology.

## AI System Design

This project demonstrates bounded multi-agent orchestration, adversarial
verification, untrusted-output validation, observable review rounds, and
concurrency controls. See the [AI system design case study](docs/ai-system-design.md)
for an evidence matrix and the design's explicit limitations.

[Atlas](https://github.com/mrinmoyece/atlas) is the companion flagship for
stateful graph execution, memory strategies, evaluation methodology, and
performance benchmarking. Its
[AI system design case study](https://github.com/mrinmoyece/atlas/blob/main/docs/ai-system-design.md)
connects those capabilities to source and measured evidence.

## Review Output Example

```markdown
## 🤖 PR Review — Score: 72/100 | Verdict: REQUEST_CHANGES

### 🔒 Security (2 issues)

**[CRITICAL]** `src/main/java/OrderController.java` line 47
SQL Injection risk — string concatenation in query.
Use parameterised queries. (OWASP A03:2021)

**[HIGH]** `src/main/java/AuthService.java` line 23
MD5 used for password hashing — use BCrypt or Argon2.

### 🏗️ Architecture (1 issue)

**[MEDIUM]** `src/main/java/ProductController.java` line 12
External HTTP call without @CircuitBreaker — add Resilience4j annotation.

### Summary
Two security issues block this merge. The SQL injection on line 47 is critical
and must be fixed before review can proceed.
```

## Quick Start

```bash
# Clone and configure
git clone https://github.com/mrinmoyece/pr-review-agent
cd pr-review-agent
cp .env.example .env
# Set separate GitHub API and model credentials, a 32+ byte webhook secret,
# GITHUB_REPOSITORY_ALLOWLIST, and a non-default GRAFANA_ADMIN_PASSWORD.
set -a
source .env
set +a

# Start the required replay store
docker compose up -d redis

# Run (Azure OpenAI - production)
./gradlew bootRun

# Run (GitHub Models - free dev mode)
./gradlew bootRun --args="--spring.profiles.active=dev"
```

### How Reviews Are Triggered

Reviews can be triggered in two ways:

---

#### 1. Automated — GitHub Webhook (recommended)

Configure a webhook on any repository you want reviewed. The agent reviews PRs in **any language or stack** — it works on the diff, not the compiled output.

**Step 1 — Deploy the agent** (once):
```bash
docker compose up -d          # starts the agent + Redis
# or: ./gradlew bootRun for local dev
```

**Step 2 — Register the webhook** on each target repository:

| Field | Value |
|---|---|
| Payload URL | `https://your-agent-host/webhook/github` |
| Content type | `application/json` |
| Secret | same value as `GITHUB_WEBHOOK_SECRET` in your `.env` |
| Events | ✅ **Pull requests** only |
| SSL | ✅ enabled (required in production) |

Go to: `https://github.com/<owner>/<repo>/settings/hooks/new`

**Step 3 — Add the repo to the allowlist** in your `.env`:
```bash
GITHUB_REPOSITORY_ALLOWLIST=owner/repo,owner/another-repo
```

Once configured, every PR `opened`, `synchronize` (new commits), or `reopened` event automatically triggers a full multi-agent review and posts the result back to the PR.

---

#### 2. Manual Trigger

The manual trigger is disabled by default. Enable it in `.env`:
```bash
REVIEW_MANUAL_TRIGGER_ENABLED=true
REVIEW_MANUAL_TRIGGER_TOKEN=<random 32+ byte secret>
```

Then call the trigger endpoint with the token and target PR:
```bash
# Trigger a review of PR #42 in any repo on your allowlist
curl -X POST \
  -H "X-Review-Trigger-Token: $REVIEW_MANUAL_TRIGGER_TOKEN" \
  "https://your-agent-host/webhook/trigger?repo=owner/repo&pr=42"

# Response
# {"status":"review_triggered","repo":"owner/repo","pr":"42"}
```

The review runs asynchronously — the curl returns `202 Accepted` immediately and the structured report appears on the PR within seconds.

> **Security note**: The manual trigger still enforces the repository allowlist and rate-limiting. It cannot review repos not explicitly in `GITHUB_REPOSITORY_ALLOWLIST`.

---

#### Supported repos

The agent works with **any GitHub repository** regardless of language or build system:
Python, TypeScript, Go, Rust, C#, Java, Terraform, Kubernetes YAML, shell scripts, etc.
Only the repository must be on the `GITHUB_REPOSITORY_ALLOWLIST` and reachable by the agent's GitHub credentials.


## Key Design Decisions

**Static analysis before LLM** — Fast deterministic checks seed six independent
specialist passes. A seventh adversarial pass challenges the aggregate result.
Every pass is bounded, auditable, and shown in the PR review. Failed or truncated
coverage cannot produce an approval.

**Untrusted by default** — PR content, history, ticket text, and model output are
treated as untrusted. Findings are schema-validated and constrained to changed
files. Repository access is default-deny, webhook deliveries are authenticated
and deduplicated. Optional fix-candidate reporting never writes repository content.

**Severity and completeness verdict** — Critical or high findings request
changes; incomplete coverage and advisory findings produce a comment; only a
complete review without findings approves. The 0-100 score summarizes severity
but does not override those safety rules. See
[Architecture](docs/architecture.md#verdict-semantics).

**Dual LLM support** — Azure OpenAI for production deployments and GitHub Models
for local development. Provider selection is explicit and fails closed when the
selected provider is not configured.

## Tech Stack

| Concern | Technology |
|---------|-----------|
| Framework | Spring Boot 3.5.16, Java 21 |
| LLM | Azure OpenAI GPT-4o / GitHub Models |
| Security Scanning | OWASP rules, CodeQL, dependency review, Gitleaks, Trivy |
| GitHub Integration | GitHub REST API + signed webhooks + refreshing GitHub App credentials |
| Resilience | Resilience4j |
| Observability | Micrometer, Prometheus |
| CI/CD | SHA-pinned GitHub Actions, CodeQL, OpenSSF Scorecard, SBOM/provenance |

## Enterprise deployment

See [Enterprise deployment](docs/enterprise-deployment.md) for least-privilege
GitHub App permissions, required repository rulesets, secret management,
network restrictions, and production rollout controls. See [SECURITY.md](SECURITY.md)
for private vulnerability reporting.

## Documentation

The [documentation index](docs/README.md) is the canonical map:

- [Architecture](docs/architecture.md)
- [AI system design](docs/ai-system-design.md)
- [Threat model](docs/threat-model.md)
- [Evaluation](docs/evaluation.md)
- [Operations and SLOs](docs/operations.md)
- [Enterprise deployment](docs/enterprise-deployment.md)
- [Architecture decisions](docs/adr/README.md)
- [Contributing](CONTRIBUTING.md) and [security policy](SECURITY.md)
