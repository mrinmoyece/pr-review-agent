# ADR-0002: Separate review executors

- Status: accepted
- Decision owners: maintainers

## Context

Outer reviews are asynchronous and each review fans out additional work. Running
both layers on the same bounded executor allows outer tasks to occupy every
worker while waiting for inner tasks that cannot start, causing starvation
deadlock under concurrent load.

## Decision

Use `reviewExecutor` for outer review lifecycles and
`reviewFanOutExecutor` for inner static, integration, and specialist tasks.
Keep both bounded, independently named, and graceful on shutdown.

## Alternatives

- **One larger pool:** delays but does not remove nested starvation risk.
- **Unbounded pools:** avoids starvation by sacrificing resource control.
- **Fully sequential reviews:** safe but unnecessarily increases latency.

## Consequences

Capacity planning must consider two pools. The design guarantees that accepted
outer work cannot consume all workers needed to make progress on its children.

## Evidence

- `src/main/java/com/agentforge/prreview/config/ReviewExecutionConfig.java`
- `src/test/java/com/agentforge/prreview/config/ReviewExecutionConfigTest.java`
