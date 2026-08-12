# Evaluation

## Evaluation contract

The repository currently validates deterministic review behavior, security
properties, orchestration progress, build quality, and deployable artifacts. It
does not yet maintain a labeled pull-request corpus for statistical LLM-quality
claims.

This document is the canonical evaluation methodology. The
[AI system design](ai-system-design.md) links here rather than defining a second
set of metrics.

## Reproducible gates

| Gate | Command or workflow | Evidence |
|---|---|---|
| Unit and concurrency behavior | `./gradlew test` | `build/reports/tests/test/` |
| Formatting and static quality | `./gradlew checkstyleMain checkstyleTest spotbugsMain spotbugsTest` | `build/reports/` |
| Coverage report | `./gradlew jacocoTestReport` | `build/reports/jacoco/test/html/` |
| Complete local build | `./gradlew clean build --no-daemon` | Exit status and reports |
| Dependency vulnerability policy | `./gradlew dependencyCheckAnalyze` when an NVD feed is available | `build/reports/dependency-check-report.html` |
| Secret history | `Secret Scan` workflow | Gitleaks SARIF/artifact |
| Dependency and image CVEs | `Dependency Scan`, `Container Scan` | Trivy SARIF |
| Data-flow security | `CodeQL` | GitHub code-scanning results |
| New dependency policy | `Dependency Review` | Pull-request check |

Tests cover positive and negative static findings, webhook signatures and
allowlists, duplicate deliveries, manual-trigger authentication, restricted
writes, and concurrent executor progress.

## LLM safety properties

The following are release requirements even though model responses vary:

- output must parse into the expected finding schema;
- findings outside changed files are discarded;
- invalid inline anchors are downgraded to general findings;
- output and diff budgets are enforced;
- failed or incomplete rounds cannot approve;
- attacker-controlled text remains inside randomized trust markers;
- fix-candidate reporting remains opt-in and autonomous writes remain prohibited.

Prompt or parser changes require fixtures for malformed output, injected
instructions, excessive findings, invalid files, and invalid line numbers.

## Quality benchmark status

No precision, recall, false-positive-rate, reviewer-agreement, token-cost, or
latency threshold is claimed for model review quality. A credible future
benchmark requires:

1. a versioned, license-compatible corpus of representative PR diffs;
2. blinded labels from at least two reviewers and an adjudication process;
3. severity and category definitions matching production output;
4. baseline and candidate model/configuration runs with fixed seeds where
   supported;
5. precision, recall, severity-weighted recall, false positives per PR,
   abstention/incomplete rate, latency, and token cost;
6. regression thresholds defined before observing candidate results;
7. stored aggregate results without proprietary source.

Until that exists, CI evidence supports engineering safety and correctness
claims, not comparative model accuracy.

## Change policy

- Deterministic rule changes require positive and negative tests.
- Prompt, model, chunking, or finding-budget changes require explicit review of
  safety properties and coverage behavior.
- Thresholds must not be relaxed solely to make a failing candidate pass.
- Generated reports remain build artifacts unless they are intentionally
  curated, reproducible, and free of sensitive data.
