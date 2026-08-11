package com.agentforge.prreview.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Verification outcome containing only specialist findings explicitly confirmed
 * by the adversarial pass.
 */
@Data
@Builder
public class AdversarialVerificationResult {
    private ReviewRoundResult round;
    private List<ReviewComment> confirmedComments;
}
