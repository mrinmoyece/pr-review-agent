# Enterprise deployment

This document owns production prerequisites and rollout policy. Architecture is
described in [Architecture](architecture.md), abuse cases in the
[Threat model](threat-model.md), and incidents and rollback in
[Operations](operations.md).

## Required identity and permissions

Use a GitHub App, not a personal access token. Install it only on approved
repositories and grant `Contents: read`, `Metadata: read`, and
`Pull requests: write`. Keep `Contents: write` disabled while auto-fix is off.
Use a separate inference-only credential for `GITHUB_MODELS_TOKEN`.

Set a random webhook secret of at least 32 bytes and configure
`GITHUB_REPOSITORY_ALLOWLIST` explicitly. Store all credentials in a managed
secret store and rotate them on a defined schedule.

Provide a highly available Redis endpoint for webhook delivery idempotency.
Every replica must use the same Redis deployment; webhook processing fails
closed when the shared delivery store is unavailable.

## Required repository rules

Create a ruleset targeting `main` with:

- pull requests required, at least one approval, code-owner review, and stale
  approval dismissal;
- required checks: `Build & Test`, `Secret Scan`, `Dependency Scan`,
  `Container Scan`, `CodeQL`, and `Dependency Review`;
- required conversation resolution and linear history;
- force pushes, deletions, and bypass access disabled except for a small
  break-glass team;
- signed commits required if the organization's signing infrastructure supports
  bot and release identities.

Enable private vulnerability reporting, Dependabot alerts and security updates,
secret scanning, push protection, validity checks, and non-provider patterns
where the GitHub plan supports them. Restrict Actions to trusted actions and
require actions to be pinned to full commit SHAs.

Publish release artifacts only through the `production` environment, restrict
that environment to protected branches, and require independent deployment
reviewers when at least two administrators are available.

## Runtime controls

Deploy the container by immutable digest. Keep the management port internal,
terminate TLS at the ingress, rate-limit the webhook route, and restrict ingress
to the GitHub webhook source where infrastructure supports it. Restrict egress
to GitHub, the selected LLM endpoint, Jira when enabled, DNS, and telemetry
destinations.

Keep `REVIEW_AUTO_FIX_ENABLED=false` unless a separate, least-privilege write
identity and mandatory human approval workflow are implemented. An incomplete
specialist round produces a non-approval result and must not satisfy a merge
gate.

## Configuration ownership

| Setting | Source | Production owner |
|---|---|---|
| GitHub App permissions and installation | GitHub organization/repository | Repository administrators |
| Repository allowlist | `GITHUB_REPOSITORY_ALLOWLIST` | Application owner |
| Webhook secret | `GITHUB_WEBHOOK_SECRET` | Security/platform secret store |
| Model provider and credential | `LLM_PROVIDER`, provider variables | AI platform owner |
| Shared replay store | `REDIS_*` | Platform owner |
| Review budgets | `REVIEW_LLM_*` | AI/application owner |
| Auto-fix | `REVIEW_AUTO_FIX_ENABLED` | Repository and security owners |
| Runtime resources and network | `k8s/deployment.yaml` overlay | Platform owner |

Use environment-specific overlays or deployment tooling; do not commit live
secrets or replace the example Kubernetes image with a mutable production tag.

## Rollout

1. Build and validate the exact commit through all required checks.
2. Publish the image through the protected `production` environment.
3. Verify SBOM/provenance and deploy by immutable digest.
4. Apply secrets and configuration through the platform secret/config system.
5. Roll out one replica, verify readiness, Redis replay behavior, GitHub API
   permissions, and a non-production test PR.
6. Continue the rollout while monitoring errors, queue pressure, provider
   throttling, and review completion.
7. Record the image digest, configuration revision, and operator.

Rollback and incident procedures are canonical in [Operations](operations.md).
The checked-in Kubernetes manifest contains a zero-digest sentinel and therefore
fails to pull until deployment automation injects the reviewed release digest.

## Production acceptance checklist

- [ ] GitHub App is repository-scoped and uses short-lived installation tokens.
- [ ] API and model credentials are separate and rotation-tested.
- [ ] Webhook HMAC, delivery IDs, repository allowlist, and Redis fail-closed
      behavior are verified.
- [ ] Management port is private and monitoring can scrape it.
- [ ] Egress is limited to approved GitHub, model, Redis, DNS, telemetry, and
      optional Jira endpoints.
- [ ] Image digest, SBOM, provenance, and vulnerability results are reviewed.
- [ ] Auto-fix is disabled or has an independent human-controlled write path.
- [ ] Alerts, on-call ownership, rollback, and credential revocation are tested.
- [ ] Data handling is approved for the selected model provider and region.
