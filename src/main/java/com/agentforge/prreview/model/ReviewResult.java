package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

/**
 * The complete output of a PR review run.
 * Posted to GitHub as a structured PR review with inline comments.
 */
@Data
@Builder
public class ReviewResult {
    private String repositoryFullName;
    private int pullRequestNumber;
    private ReviewVerdict verdict;
    /** Overall code quality score 0–100 */
    private int overallScore;
    private List<ReviewComment> comments;
    private SecuritySummary securitySummary;
    /** LLM-generated executive summary for the PR review body */
    private String executiveSummary;
    /** Outcome of Jira ticket alignment check. Empty if Jira not configured. */
    private TicketAlignment ticketAlignment;
    /** Auto-fixes committed to the PR branch during this review. */
    private List<AutoFix> autoFixesApplied;
    /** Auditable status and output of every LLM specialist pass. */
    private List<ReviewRoundResult> reviewRounds;
    /** False when any required analysis pass failed or input was truncated. */
    private boolean reviewComplete;
    private Instant reviewedAt;

    public enum ReviewVerdict {
        APPROVE,          // All checks pass, no blocking issues
        COMMENT,          // Non-blocking issues found
        REQUEST_CHANGES   // Blocking issues — must not merge
    }

    @Data
    @Builder
    public static class SecuritySummary {
        private int criticalCount;
        private int highCount;
        private int mediumCount;
    }
}
