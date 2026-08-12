package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Auditable outcome of one specialist review pass.
 */
@Data
@Builder
public class ReviewRoundResult {
    private ReviewPass pass;
    private RoundStatus status;
    private String model;
    private int chunksReviewed;
    private List<ReviewComment> comments;
    private String detail;

    public enum RoundStatus {
        COMPLETE,
        TRUNCATED,
        FAILED
    }
}
