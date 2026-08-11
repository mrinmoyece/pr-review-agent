# Enterprise deployment

## Required identity and permissions

Use a GitHub App, not a personal access token. Install it only on approved
repositories and grant `Contents: read`, `Metadata: read`, and
`Pull requests: write`. Keep `Contents: write` disabled while auto-fix is off.
Use a separate inference-only credential for `GITHUB_MODELS_TOKEN`.
Add an `NVD_API_KEY` Actions secret so the required dependency scan can update
its vulnerability database reliably without public-feed rate limiting.

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
