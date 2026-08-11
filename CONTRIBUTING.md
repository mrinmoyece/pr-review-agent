# Contributing

Use Java 21 and the checked-in Gradle wrapper.

```bash
./gradlew build
```

Changes to webhooks, prompts, GitHub permissions, write-capable tools, workflows,
or deployment manifests require a security-focused review from CODEOWNERS.
Add tests for success, rejection, timeout, malformed model output, and
prompt-injection cases when changing an agent boundary.

Commits should be focused and pull requests must complete the repository
template. Never include credentials, production payloads, or proprietary source
code in tests or prompts.
