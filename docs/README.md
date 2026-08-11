# Documentation

Each topic has one canonical owner. Other documents link here instead of
copying operational or architectural detail.

| Topic | Canonical document | Audience |
|---|---|---|
| Product overview and local start | [Project README](../README.md) | Users and evaluators |
| System architecture and data flow | [Architecture](architecture.md) | Engineers and reviewers |
| LLM orchestration and competency evidence | [AI system design](ai-system-design.md) | AI engineers and evaluators |
| Security boundaries and abuse cases | [Threat model](threat-model.md) | Security and platform teams |
| Vulnerability reporting | [Security policy](../SECURITY.md) | Reporters and maintainers |
| Evaluation methodology and evidence | [Evaluation](evaluation.md) | AI, quality, and release teams |
| SLOs, telemetry, incidents, and runbooks | [Operations](operations.md) | Operators and on-call engineers |
| Production rollout and repository controls | [Enterprise deployment](enterprise-deployment.md) | Platform and release teams |
| Engineering decisions | [ADRs](adr/README.md) | Maintainers |
| Development and change validation | [Contributing](../CONTRIBUTING.md) | Contributors |

## Documentation rules

- Describe only behavior supported by source, tests, configuration, or measured
  artifacts.
- Link to the canonical topic instead of repeating it.
- Label proposed targets and future work explicitly.
- Update documentation in the same change as behavior, configuration, or
  operational ownership.
- Verify relative links and documented commands before merge.
