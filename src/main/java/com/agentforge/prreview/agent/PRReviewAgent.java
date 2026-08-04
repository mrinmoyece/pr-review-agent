package com.agentforge.prreview.agent;

import com.agentforge.prreview.exception.ReviewAgentException;
import com.agentforge.prreview.model.*;
import com.agentforge.prreview.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * PR Review Agent — orchestrates multi-tool review pipeline.
 *
 * Review pipeline:
 *   1. Fetch PR diff from GitHub
 *   2. PARALLEL: [security, arch, perf] + jira alignment check + load team patterns
 *   3. LLM holistic review (with team patterns for context)
 *   4. Determine verdict + score
 *   5. Auto-fix eligible LOW/MEDIUM comments (commits to branch)
 *   6. Post all comments to GitHub PR
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PRReviewAgent {

    private final GitHubDiffTool gitHubDiffTool;
    private final SecurityScanTool securityScanTool;
    private final ArchitectureCheckTool architectureCheckTool;
    private final PerformanceAnalysisTool performanceAnalysisTool;
    private final LLMReviewTool llmReviewTool;
    private final GitHubCommentTool gitHubCommentTool;
    private final JiraTicketTool jiraTicketTool;
    private final ReviewHistoryTool reviewHistoryTool;
    private final GitHubAutoFixTool gitHubAutoFixTool;

    @Async
    public CompletableFuture<ReviewResult> review(String repoFullName, int prNumber,
                                                   String headBranch, String prTitle, String prBody) {
        log.info("Starting PR review — {}/#{}", repoFullName, prNumber);

        try {
            // STEP 1: Fetch diff
            log.info("[ACT] Fetching PR diff");
            String diff = gitHubDiffTool.fetchDiff(repoFullName, prNumber);
            List<DiffFile> changedFiles = gitHubDiffTool.parseDiff(diff);
            log.info("[OBSERVE] {} files changed in PR", changedFiles.size());

            // STEP 2: Run static tools, Jira check, and history loading in parallel
            CompletableFuture<Optional<TicketAlignment>> jiraFuture = CompletableFuture.supplyAsync(
                    () -> jiraTicketTool.checkAlignment(prTitle, prBody, diff));
            CompletableFuture<String> patternsFuture = CompletableFuture.supplyAsync(
                    () -> reviewHistoryTool.loadTeamPatterns(repoFullName));
            CompletableFuture<List<ReviewComment>> secFuture = CompletableFuture.supplyAsync(
                    () -> securityScanTool.scan(changedFiles));
            CompletableFuture<List<ReviewComment>> archFuture = CompletableFuture.supplyAsync(
                    () -> architectureCheckTool.check(changedFiles));
            CompletableFuture<List<ReviewComment>> perfFuture = CompletableFuture.supplyAsync(
                    () -> performanceAnalysisTool.analyse(changedFiles));

            CompletableFuture.allOf(jiraFuture, patternsFuture, secFuture, archFuture, perfFuture).join();

            List<ReviewComment> securityComments = secFuture.get();
            List<ReviewComment> allComments = new ArrayList<>();
            allComments.addAll(securityComments);
            allComments.addAll(archFuture.get());
            allComments.addAll(perfFuture.get());

            log.info("[OBSERVE] Static analysis: {} total issues found", allComments.size());

            // STEP 3: LLM holistic review with team patterns
            String teamPatterns = patternsFuture.get();
            log.info("[ACT] Running LLM review for context-aware analysis");
            List<ReviewComment> llmComments = llmReviewTool.review(diff, allComments, teamPatterns);
            allComments.addAll(llmComments);
            log.info("[OBSERVE] LLM added {} additional comments", llmComments.size());

            // STEP 4: Determine verdict + score
            ReviewResult.ReviewVerdict verdict = determineVerdict(allComments);
            int score = calculateScore(allComments);

            // STEP 5: Auto-fix eligible LOW/MEDIUM comments
            log.info("[ACT] Attempting auto-fix for eligible comments");
            List<AutoFix> autoFixes = gitHubAutoFixTool.applyFixes(repoFullName, headBranch, allComments);

            ReviewResult result = ReviewResult.builder()
                    .repositoryFullName(repoFullName)
                    .pullRequestNumber(prNumber)
                    .verdict(verdict)
                    .overallScore(score)
                    .comments(allComments)
                    .securitySummary(buildSecuritySummary(securityComments))
                    .executiveSummary(llmReviewTool.generateSummary(allComments, verdict, score))
                    .ticketAlignment(jiraFuture.get().orElse(null))
                    .autoFixesApplied(autoFixes)
                    .build();

            // STEP 6: Post all comments to GitHub
            log.info("[ACT] Posting {} comments to PR #{}", allComments.size(), prNumber);
            gitHubCommentTool.postReview(repoFullName, prNumber, result);
            log.info("[OBSERVE] Review posted — verdict: {}, score: {}/100", verdict, score);

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("PR review failed for {}/#{}: {}", repoFullName, prNumber, e.getMessage(), e);
            throw new ReviewAgentException("PR review failed", e);
        }
    }

    private ReviewResult.ReviewVerdict determineVerdict(List<ReviewComment> comments) {
        boolean hasCritical = comments.stream()
                .anyMatch(c -> c.getSeverity() == ReviewComment.CommentSeverity.CRITICAL);
        boolean hasHigh = comments.stream()
                .anyMatch(c -> c.getSeverity() == ReviewComment.CommentSeverity.HIGH &&
                               c.getCategory() == ReviewComment.CommentCategory.SECURITY);
        if (hasCritical || hasHigh) return ReviewResult.ReviewVerdict.REQUEST_CHANGES;

        boolean hasMedium = comments.stream()
                .anyMatch(c -> c.getSeverity() == ReviewComment.CommentSeverity.MEDIUM);
        return hasMedium ? ReviewResult.ReviewVerdict.COMMENT : ReviewResult.ReviewVerdict.APPROVE;
    }

    private int calculateScore(List<ReviewComment> comments) {
        int deductions = comments.stream().mapToInt(c -> switch (c.getSeverity()) {
            case CRITICAL -> 25;
            case HIGH -> 15;
            case MEDIUM -> 5;
            case LOW -> 2;
            default -> 0;
        }).sum();
        return Math.max(0, 100 - deductions);
    }

    private ReviewResult.SecuritySummary buildSecuritySummary(List<ReviewComment> secComments) {
        return ReviewResult.SecuritySummary.builder()
                .criticalCount((int) secComments.stream()
                        .filter(c -> c.getSeverity() == ReviewComment.CommentSeverity.CRITICAL).count())
                .highCount((int) secComments.stream()
                        .filter(c -> c.getSeverity() == ReviewComment.CommentSeverity.HIGH).count())
                .mediumCount((int) secComments.stream()
                        .filter(c -> c.getSeverity() == ReviewComment.CommentSeverity.MEDIUM).count())
                .build();
    }
}
