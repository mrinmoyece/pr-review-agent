package com.agentforge.prreview.model;

/**
 * Independent review perspectives executed by the agent.
 */
public enum ReviewPass {
    SECURITY("security", "authorization, data exposure, injection, secrets, supply-chain and trust-boundary flaws"),
    CORRECTNESS("correctness", "logic errors, edge cases, concurrency, state transitions and error handling"),
    TESTING("testing", "missing regression tests, weak assertions, boundary coverage and testability"),
    ARCHITECTURE("architecture", "coupling, API compatibility, data ownership, maintainability and design consistency"),
    PERFORMANCE("performance", "complexity, database access, blocking work, resource bounds and scalability"),
    OPERATIONS("operations", "configuration safety, observability, rollout, reliability and backward compatibility"),
    ADVERSARIAL_VERIFICATION("verification",
            "challenge earlier findings, identify missed high-impact defects and reject unsupported claims");

    private final String key;
    private final String focus;

    ReviewPass(String key, String focus) {
        this.key = key;
        this.focus = focus;
    }

    public String key() {
        return key;
    }

    public String focus() {
        return focus;
    }
}
