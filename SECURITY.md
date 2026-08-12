# Security Policy

## Supported versions

Security fixes are applied to the latest release and the `main` branch.

## Reporting a vulnerability

Do not open a public issue. Use GitHub private vulnerability reporting from the
repository's **Security** tab. Include affected versions, reproduction steps,
impact, and any proposed mitigation.

You should receive an acknowledgement within three business days. We will
coordinate disclosure after a fix is available and will credit reporters who
want attribution.

## Security boundaries

Deploy the service in GitHub App mode, installed only on approved repositories.
The runtime mints and refreshes short-lived installation tokens; static token
mode is for local development only. Fix-candidate reporting is disabled by
default and never writes repository content. PR diffs, ticket text, review
history, and model output are untrusted and must never grant authorization.

The canonical assets, actors, abuse cases, controls, residual risks, and privacy
assumptions are documented in the [threat model](docs/threat-model.md).
Containment, rotation, and recovery procedures are in
[Operations](docs/operations.md).
