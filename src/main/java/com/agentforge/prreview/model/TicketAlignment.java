package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

/**
 * Result of comparing a PR's diff against the linked Jira ticket requirements.
 */
@Getter
@Builder
public class TicketAlignment {
    private String ticketKey;
    /** 0-100: how well the diff covers the ticket's stated requirements */
    private int alignmentScore;
    private Verdict verdict;
    /** Requirements stated in the ticket but not found in the diff */
    private List<String> missingRequirements;
    /** Changes in the diff that appear unrelated to the ticket */
    private List<String> unrequiredChanges;
    private String summary;

    public enum Verdict {
        ALIGNED,    // Diff covers all ticket requirements; no unrelated changes
        PARTIAL,    // Mostly aligned but has gaps or minor scope creep
        MISALIGNED  // Significant mismatch -- wrong implementation or major missing work
    }
}
