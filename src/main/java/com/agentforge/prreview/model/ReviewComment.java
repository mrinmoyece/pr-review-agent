package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Data;

/**
 * A single review comment with severity, category, and optional inline line reference.
 */
@Data
@Builder
public class ReviewComment {
    private String filename;
    /** Optional: line number for inline GitHub PR comment */
    private Integer lineNumber;
    private CommentCategory category;
    private CommentSeverity severity;
    /** Short title shown in the PR comment header */
    private String title;
    /** Full explanation with context and fix guidance */
    private String body;
    /** Whether tooling can auto-fix this without human review */
    private boolean autoFixable;
    /** Optional: a concrete code snippet fixing this issue. Populated for autoFixable=true comments. */
    private String suggestedFix;

    public enum CommentCategory {
        SECURITY, PERFORMANCE, ARCHITECTURE, STYLE, TEST_COVERAGE, CORRECTNESS
    }

    public enum CommentSeverity {
        CRITICAL,   // Must block merge — potential data breach or service failure
        HIGH,       // Should block merge — significant risk
        MEDIUM,     // Should be addressed before merge
        LOW,        // Nice to have
        INFO        // Informational, no action required
    }
}
