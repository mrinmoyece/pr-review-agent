package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Records the outcome of an auto-fix attempt for a single file.
 */
@Getter
@Builder
public class AutoFix {
    private String filename;
    private boolean applied;
    private String commitSha;
    private String commitMessage;
    private int issuesFixed;
    private String skipReason;

    public static AutoFix committed(String filename, String sha, String message, int count) {
        return AutoFix.builder()
                .filename(filename).applied(true)
                .commitSha(sha).commitMessage(message).issuesFixed(count)
                .build();
    }

    public static AutoFix skipped(String filename, String reason) {
        return AutoFix.builder()
                .filename(filename).applied(false).skipReason(reason)
                .build();
    }
}
