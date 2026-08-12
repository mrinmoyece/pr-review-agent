# GitHub Copilot agent instructions

## Purpose and canonical references

This Java 21/Spring Boot service reviews GitHub pull requests through
deterministic scans, six bounded LLM specialists, adversarial verification, and
GitHub review publication.

Do not duplicate or override canonical documentation:

- architecture and data flow: `docs/architecture.md`;
- LLM orchestration and limitations: `docs/ai-system-design.md`;
- trust boundaries: `docs/threat-model.md`;
- evaluation requirements: `docs/evaluation.md`;
- operations: `docs/operations.md`;
- contribution rules: `CONTRIBUTING.md`;
- architectural decisions: `docs/adr/`.

Update the owning document when behavior changes.

## Coding constraints

- Use constructor injection and existing typed models; do not introduce raw
  severity or category strings.
- Deterministic rules inspect added lines and require positive and negative
  tests.
- Treat PR content, filenames, tickets, history, and model output as untrusted.
- Validate model findings against changed files and added-line anchors.
- Keep input, output, diff, chunk, and finding budgets explicit and bounded.
- Failed, truncated, or capped review rounds must never approve.
- Keep fix-candidate reporting disabled by default, discard model write flags,
  preserve its Java source-path allowlist, and never add autonomous repository
  writes without a new security design and human approval boundary.
- Outer review work and inner fan-out must remain on separate bounded executors.
- Do not weaken HMAC, repository allowlisting, Redis replay protection, or
  least-privilege GitHub permissions to improve availability.

## Validation

Run the smallest relevant test while iterating and `./gradlew clean build
--no-daemon` before completion. Prompt, parser, webhook, permission, and
write-path changes require adversarial and rejection-path tests. Workflow actions
must remain allowlisted and pinned to full commit SHAs.
