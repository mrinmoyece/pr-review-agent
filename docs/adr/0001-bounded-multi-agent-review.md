# ADR-0001: Bounded multi-agent review

- Status: accepted
- Decision owners: maintainers

## Context

A single holistic model call provides poor failure isolation, weak coverage
evidence, and no independent challenge of plausible findings. Unbounded review
input and output also create cost, latency, and denial-of-service risk.

## Decision

Run deterministic scans first, then six focused specialist passes in parallel,
followed by one adversarial verification pass over the aggregate. The verifier
always inspects the diff, even when specialists return no candidates, and must
explicitly confirm or reject every candidate; rejected findings do not affect
the verdict. If verification cannot inspect complete evidence or fails,
candidates remain visible and the review is incomplete. Bound diff
characters, chunk size, chunk count, output tokens, and findings. Record every
round's model, status, chunk count, details, and findings. Any failed, truncated,
or capped round prevents approval. Verify candidate decisions in bounded batches;
only the first batch performs missed-finding discovery, preventing default-valid
candidate volumes from overflowing one model response.

## Alternatives

- **One holistic call:** simpler and cheaper to operate, but provides weaker
  coverage attribution and challenge.
- **Unbounded autonomous agents:** flexible, but inappropriate for untrusted PR
  input and predictable CI cost.
- **Deterministic checks only:** reliable but unable to assess broader semantic
  correctness and architecture.

## Consequences

The design increases model calls and orchestration complexity. In exchange,
review coverage is visible, specialist failures are isolated, and incomplete
work has safe verdict semantics.

## Evidence

- `PRReviewAgent`
- `LLMReviewTool`
- `ReviewRoundResult`
- `ReviewExecutionConfigTest`
