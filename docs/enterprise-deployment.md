# Enterprise deployment

This document owns production prerequisites and rollout policy. Architecture is
described in [Architecture](architecture.md), abuse cases in the
[Threat model](threat-model.md), and incidents and rollback in
[Operations](operations.md).

## Required identity and permissions

Use a GitHub App, not a personal access token. Install it only on approved
repositories and grant `Contents: read`, `Metadata: read`, and
`Pull requests: write`. Keep `Contents: write` disabled; the agent does not write
repository content.
Set `GITHUB_AUTH_MODE=app`, `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, and
`GITHUB_APP_PRIVATE_KEY` (GitHub PKCS#1 or PKCS#8 RSA PEM). The runtime signs a short-lived app JWT,
mints an installation token, and refreshes it five minutes before expiry. Static
`GITHUB_TOKEN` mode is intended only for local development.
Use a separate inference-only credential for `GITHUB_MODELS_TOKEN`.

Set a random webhook secret of at least 32 bytes and configure
`GITHUB_REPOSITORY_ALLOWLIST` explicitly. Store all credentials in a managed
secret store and rotate them on a defined schedule.

Provide a highly available Redis endpoint for authenticated webhook replay state.
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

The checked-in network policy admits port 8080 only from labeled
`ingress-nginx` pods and port 9090 only from labeled Prometheus pods. It permits
same-namespace labeled Redis and `kube-system` DNS. External HTTPS uses the
fail-closed documentation-range sentinel `192.0.2.1/32`; deployment automation
must replace it with approved API CIDRs or an equivalent platform FQDN policy.
Adjust namespace and pod labels to the actual platform before rollout. External
Redis similarly requires an explicit approved CIDR/FQDN overlay.

Keep `REVIEW_AUTO_FIX_ENABLED=false` unless fix-candidate reporting is wanted;
the agent never writes repository content. An incomplete specialist round
produces a non-approval result and must not satisfy a merge gate.

## Configuration ownership

| Setting | Source | Production owner |
|---|---|---|
| GitHub App permissions and installation | GitHub organization/repository | Repository administrators |
| GitHub API authentication | `GITHUB_AUTH_MODE`, `GITHUB_APP_*` | Application/platform owners |
| Repository allowlist | `GITHUB_REPOSITORY_ALLOWLIST` | Application owner |
| Webhook secret | `GITHUB_WEBHOOK_SECRET` | Security/platform secret store |
| Model provider and credential | `LLM_PROVIDER`, provider variables | AI platform owner |
| Shared replay store | `REDIS_*` | Platform owner |
| Review budgets | `REVIEW_LLM_*` | AI/application owner |
| Fix-candidate reporting | `REVIEW_AUTO_FIX_ENABLED` | Repository and security owners |
| Runtime resources and network | `k8s/deployment.yaml` overlay | Platform owner |

Use environment-specific overlays or deployment tooling; do not commit live
secrets or replace the example Kubernetes image with a mutable production tag.

## Rollout

1. Build and validate the exact commit through all required checks.
2. Build a candidate through the protected `production` environment, scan its
   exact digest, and promote that same digest to the immutable commit tag.
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
- [ ] Webhook HMAC, authenticated replay keys, delivery metadata, repository allowlist, and Redis fail-closed
      behavior are verified.
- [ ] Management port is private and monitoring can scrape it.
- [ ] Egress is limited to approved GitHub, model, Redis, DNS, telemetry, and
      optional Jira endpoints.
- [ ] The promoted image digest exactly matches the digest scanned after build;
  its SBOM, provenance, and vulnerability results are reviewed.
- [ ] Repository `Contents: write` is disabled and fix candidates require human action.
- [ ] Alerts, on-call ownership, rollback, and credential revocation are tested.
- [ ] Data handling is approved for the selected model provider and region.
