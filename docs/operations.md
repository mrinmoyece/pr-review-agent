# Operations

## Service indicators and objectives

The repository exposes framework health and Micrometer metrics, plus structured
review lifecycle logs. It does not yet emit dedicated counters or histograms for
review latency, model tokens, or publication outcomes. The objectives below are
recommended initial production policy, not claims measured by this repository.

| SLI | Suggested initial objective | Current signal |
|---|---|---|
| Webhook endpoint availability | 99.9% over 30 days | HTTP status and ingress metrics |
| Accepted-to-published review latency | 95% within 10 minutes | Correlated application logs; custom histogram is future work |
| Complete review rate | 99% excluding provider incidents and configured caps | Published round statuses |
| Authenticated payload replay suppression | 100% for retained replay keys | HTTP 409 and Redis state |
| Review publication success | 99.5% of completed reviews | Application logs; custom counter is future work |

Do not page on model-quality judgments. Page on availability, sustained backlog,
publication failure, replay-store failure, and security-control degradation.

## Health and telemetry

- Application traffic: port 8080.
- Health and Prometheus: management port 9090.
- Readiness: `/actuator/health/readiness`.
- Liveness: `/actuator/health/liveness`.
- Metrics: `/actuator/prometheus`.
- Prometheus configuration: `observability/prometheus.yml`.

Management traffic must remain internal. Production monitoring should scrape
through an authenticated/private network path and narrow Kubernetes ingress to
the monitoring namespace.

## Operational checks

```bash
# Local stack
docker compose config
docker compose up --build

# Health and metrics inside the app container
docker compose exec app curl --fail http://localhost:9090/actuator/health
docker compose exec app curl --fail http://localhost:9090/actuator/prometheus

# Application and Redis logs
docker compose logs app redis
```

Port 9090 is used by the bundled Prometheus container on the host, so application
management is queried inside the app container in this topology.

## Runbooks

### Webhooks are rejected

1. Inspect status: 401 indicates signature failure, 403 an unapproved repository,
   409 a duplicate delivery, 413 an oversized payload.
2. Confirm the webhook secret matches GitHub without printing it.
3. Confirm `GITHUB_REPOSITORY_ALLOWLIST` uses exact `owner/repository` names.
4. Check GitHub delivery headers and redeliver only after resolving the cause.
5. Never disable HMAC or replay checks to restore traffic.

### Redis is unavailable

1. Expect new webhook processing to fail closed.
2. Check DNS, network policy, TLS/password configuration, latency, and capacity.
3. Restore the shared Redis service; do not switch replicas to local stores.
4. Redeliver failed GitHub events after health is stable.
5. Verify an in-progress or successful repeated delivery receives 409. A failed
   review releases its reservation and may be redelivered.

### Model rounds fail or truncate

1. Inspect published round detail and sanitized logs for provider errors or caps.
2. Check provider quota, deployment name, credential, endpoint, and latency.
3. Confirm diff and finding budgets are appropriate; do not raise them without a
   capacity and cost review.
4. Preserve the non-approval result and rerun after recovery.

### Reviews complete but are not posted

1. Check GitHub App installation, repository access, token expiry, and
   `Pull requests: write`.
2. Check API rate limits and GitHub status.
3. Re-run with a fresh installation token after correcting permissions.
4. Confirm the original result did not appear before retrying to avoid duplicate
   review noise.

### Queue saturation or high latency

1. Compare incoming webhook rate with executor queue capacity and provider
   latency.
2. Check CPU, memory, Redis latency, GitHub rate limits, and model throttling.
3. Scale replicas only when all share Redis and provider quotas support it.
4. Reduce accepted repository scope or traffic before increasing model budgets.
5. Capture a profile or thread dump before changing executor sizes.

## Incident response

1. **Detect:** alert on control degradation, error spikes, sustained backlog, or
   unauthorized review/write activity.
2. **Contain:** disable the webhook or GitHub App installation; disable fix-candidate reporting;
   revoke affected credentials.
3. **Preserve:** retain sanitized logs, GitHub delivery IDs, review URLs, image
   digest, configuration version, and timeline.
4. **Eradicate:** patch the root cause, rotate secrets, and invalidate images or
   tokens.
5. **Recover:** deploy by immutable digest, verify health/replay controls, and
   redeliver only known failed events.
6. **Learn:** document impact, contributing controls, detection gaps, and owned
   follow-ups without placing secrets in the postmortem.

Security reports and disclosure follow [SECURITY.md](../SECURITY.md).

## Rollback

Rollback to the last approved immutable image digest. Keep database/state
compatibility in mind: replay keys in Redis are opaque and expiring. A rollback
across the delivery-ID-to-payload-hash key transition temporarily loses prior
deduplication state, so pause webhook traffic during that rollback. Afterward,
verify readiness, metrics, repository allowlisting, replay rejection, and a
non-production test PR before restoring normal traffic.

## Secret rotation

Rotate GitHub App credentials, model credentials, webhook secret, Redis
credentials, and optional Jira token independently. During webhook-secret
rotation, coordinate GitHub and service rollout to avoid an unsigned acceptance
window. Revoke old credentials after the new path is verified.
