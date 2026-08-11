# ADR-0003: Shared fail-closed replay defense

- Status: accepted
- Decision owners: maintainers

## Context

GitHub retries webhook deliveries, and an attacker may replay a previously valid
signed payload. In-memory delivery tracking differs across replicas and is lost
on restart.

## Decision

Require a GitHub delivery ID and reserve it through Redis using an atomic insert
with expiry. Every replica uses the same store. If reservation cannot be
verified, reject processing rather than bypassing replay protection.

## Alternatives

- **In-memory cache:** simple but replica-local and restart-unsafe.
- **Process despite Redis failure:** improves availability but silently removes a
  security invariant.
- **Permanent delivery records:** durable but creates unbounded retention and
  unnecessary personal-data handling.

## Consequences

Redis becomes an availability dependency for webhook acceptance. Expiry bounds
storage while covering the configured replay window. Operators must restore
Redis and redeliver failed events after outages.

## Evidence

- `RedisWebhookDeliveryStore`
- `WebhookController`
- `RedisWebhookDeliveryStoreTest`
