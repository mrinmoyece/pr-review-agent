# PR Review Agent 🔍

> AI-powered pull request reviewer for Spring Boot / Java microservices. Detects security vulnerabilities, architecture violations, and performance issues — and posts structured feedback directly to your GitHub PRs.

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

```
┌──────────────────────────────────────────────────────────┐
│       GitHub PR Event (opened / synchronize / reopened)  │
└─────────────────────────┬────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│                    PRReviewAgent                         │
│                                                          │
│  Tool 1: GitHubDiffTool    ── fetch & parse PR diff      │
│  Tool 2: SecurityScanTool  ── OWASP pattern matching     │
│  Tool 3: ArchCheckTool     ── Spring Boot patterns       │
│  Tool 4: PerfAnalysisTool  ── N+1, sync, memory         │
│  Tool 5: LLMReviewTool     ── parallel specialists       │
│  Tool 6: GitHubCommentTool ── post structured feedback   │
└──────────────────────────────────────────────────────────┘
```

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
cp .env.example .env
# Set separate GitHub API and model credentials, a 32+ byte webhook secret,
# and GITHUB_REPOSITORY_ALLOWLIST.

# Run (Azure OpenAI — production)
./gradlew bootRun

# Run (GitHub Models — free dev mode)
./gradlew bootRun --args="--spring.profiles.active=dev"
```

### How a Review Gets Triggered
The review runs automatically — there is no manual command to type. Once the GitHub webhook
is configured to point at `/webhook/github`, every PR `opened`, `synchronize` (new commits pushed),
or `reopened` event triggers a full review pipeline asynchronously, and the result is posted back
as a GitHub PR review with inline comments.

The manual trigger is disabled by default. If explicitly enabled, it requires
`X-Review-Trigger-Token` and still enforces the repository allowlist:
```bash
curl -X POST \
  -H "X-Review-Trigger-Token: $REVIEW_MANUAL_TRIGGER_TOKEN" \
  "http://localhost:8080/webhook/trigger?repo=mrinmoyece/some-repo&pr=42"
```

## Key Design Decisions

**Static analysis before LLM** — Fast deterministic checks seed six independent
specialist passes. A seventh adversarial pass challenges the aggregate result.
Every pass is bounded, auditable, and shown in the PR review. Failed or truncated
coverage cannot produce an approval.

**Untrusted by default** — PR content, history, ticket text, and model output are
treated as untrusted. Findings are schema-validated and constrained to changed
files. Repository access is default-deny, webhook deliveries are authenticated
and deduplicated, and auto-fix is disabled unless explicitly enabled.

**Score-based verdict** — Rather than a binary approve/reject, every review produces a 0–100 score. Teams can configure their own threshold: block merges below 70, warn below 85.

**Dual LLM support** — Azure OpenAI in CI/CD pipelines, GitHub Models for local dev and open source contributors.

## Tech Stack

| Concern | Technology |
|---------|-----------|
| Framework | Spring Boot 3.4.6, Java 21 |
| LLM | Azure OpenAI GPT-4o / GitHub Models |
| Security Scanning | OWASP rules, CodeQL, dependency review, Gitleaks, Trivy |
| GitHub Integration | kohsuke/github-api + Webhooks |
| Resilience | Resilience4j |
| Observability | Micrometer, Prometheus |
| CI/CD | SHA-pinned GitHub Actions, CodeQL, OpenSSF Scorecard, SBOM/provenance |

## Enterprise deployment

See [Enterprise deployment](docs/enterprise-deployment.md) for least-privilege
GitHub App permissions, required repository rulesets, secret management,
network restrictions, and production rollout controls. See [SECURITY.md](SECURITY.md)
for private vulnerability reporting.
