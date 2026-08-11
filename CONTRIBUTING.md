# Contributing

Use Java 21 and the checked-in Gradle wrapper. Start with the
[documentation map](docs/README.md), [architecture](docs/architecture.md), and
[AI system design](docs/ai-system-design.md).

## Local development

Copy `.env.example` to `.env`, provide non-production credentials, start Redis,
and run the application:

```bash
docker compose up -d redis
./gradlew bootRun
```

Use a webhook tunnel only with a dedicated development GitHub App and repository
allowlist. Never point local development at production credentials or broad
repository access.

## Validation

```bash
./gradlew clean build --no-daemon
```

This runs tests, Checkstyle, SpotBugs, and coverage report generation. Use the
smallest targeted test while iterating, then run the complete build before a
pull request. Security and release evidence is defined in
[Evaluation](docs/evaluation.md).

## Change requirements

Changes to webhooks, prompts, GitHub permissions, write-capable tools, workflows,
or deployment manifests require a security-focused review from CODEOWNERS.
Add tests for success, rejection, timeout, malformed model output, and
prompt-injection cases when changing an agent boundary.

Update the canonical document that owns changed behavior. Do not copy the same
explanation into the README, agent instructions, and operations guide. Major,
costly-to-reverse decisions require an [ADR](docs/adr/README.md).

Commits should be focused and pull requests must complete the repository
template. Never include credentials, production payloads, or proprietary source
code in tests or prompts.
