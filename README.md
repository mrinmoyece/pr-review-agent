# PR Review Agent 🔍

> AI-powered pull request reviewer for Spring Boot / Java microservices. Detects security vulnerabilities, architecture violations, and performance issues — and posts structured feedback directly to your GitHub PRs.

[![CI](https://github.com/mrinmoyece/pr-review-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/mrinmoyece/pr-review-agent/actions)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![OWASP](https://img.shields.io/badge/OWASP-Top%2010-red)](https://owasp.org/www-project-top-ten/)

## What It Does

The agent listens for GitHub `pull_request` webhook events (`opened`, `synchronize`, `reopened`) and automatically analyses the diff across four dimensions whenever a PR is opened or pushed to — no manual trigger needed:

| Dimension | What It Checks |
|-----------|---------------|
| 🔒 Security | OWASP Top 10: SQL injection, weak crypto, hardcoded secrets, missing auth, unvalidated input |
| 🏗️ Architecture | RestTemplate deprecation, missing circuit breakers, controller→repo coupling, @Transactional misuse |
| ⚡ Performance | N+1 queries, unnecessary synchronisation, missing pagination, blocking I/O in reactive chains |
| 🧠 LLM Review | Business logic correctness, edge cases, error handling gaps — what rules can't catch |

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
│  Tool 5: LLMReviewTool     ── context-aware holistic     │
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
# Edit .env with your tokens

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

For local testing without wiring up a real webhook, use the manual trigger endpoint instead:
```bash
curl -X POST "http://localhost:8080/webhook/trigger?repo=mrinmoyece/some-repo&pr=42"
```

## Key Design Decisions

**Static analysis before LLM** — Pattern matching runs first and is essentially instant. The LLM only runs on the remaining diff to catch nuanced issues the patterns missed. This reduces LLM token cost by ~50% on typical PRs while keeping coverage high.

**Score-based verdict** — Rather than a binary approve/reject, every review produces a 0–100 score. Teams can configure their own threshold: block merges below 70, warn below 85.

**Dual LLM support** — Azure OpenAI in CI/CD pipelines, GitHub Models for local dev and open source contributors.

## Tech Stack

| Concern | Technology |
|---------|-----------|
| Framework | Spring Boot 3.4.6, Java 21 |
| LLM | Azure OpenAI GPT-4o / GitHub Models |
| Security Scanning | Custom OWASP pattern engine |
| GitHub Integration | kohsuke/github-api + Webhooks |
| Resilience | Resilience4j |
| Observability | Micrometer, Prometheus |
| CI/CD | GitHub Actions + Semgrep + Snyk |
