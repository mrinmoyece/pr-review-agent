package com.agentforge.prreview.agent;

import com.agentforge.prreview.exception.ReviewAgentException;
import com.agentforge.prreview.model.AdversarialVerificationResult;
import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewPass;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.model.ReviewRoundResult;
import com.agentforge.prreview.model.TicketAlignment;
import com.agentforge.prreview.tool.ArchitectureCheckTool;
import com.agentforge.prreview.tool.GitHubAutoFixTool;
import com.agentforge.prreview.tool.GitHubCommentTool;
import com.agentforge.prreview.tool.GitHubDiffTool;
import com.agentforge.prreview.tool.JiraTicketTool;
import com.agentforge.prreview.tool.LLMReviewTool;
import com.agentforge.prreview.tool.PerformanceAnalysisTool;
import com.agentforge.prreview.tool.ReviewHistoryTool;
import com.agentforge.prreview.tool.SecurityScanTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Orchestrates static analysis, parallel specialist reviews, adversarial verification and reporting.
 */
@Component
@Slf4j
public class PRReviewAgent {

    private static final List<ReviewPass> SPECIALIST_PASSES = List.of(
            ReviewPass.SECURITY,
            ReviewPass.CORRECTNESS,
            ReviewPass.TESTING,
            ReviewPass.ARCHITECTURE,
            ReviewPass.PERFORMANCE,
            ReviewPass.OPERATIONS
    );

    private final GitHubDiffTool gitHubDiffTool;
    private final SecurityScanTool securityScanTool;
    private final ArchitectureCheckTool architectureCheckTool;
    private final PerformanceAnalysisTool performanceAnalysisTool;
    private final LLMReviewTool llmReviewTool;
    private final GitHubCommentTool gitHubCommentTool;
    private final JiraTicketTool jiraTicketTool;
    private final ReviewHistoryTool reviewHistoryTool;
    private final GitHubAutoFixTool gitHubAutoFixTool;
    @Qualifier("reviewFanOutExecutor")
    private final Executor reviewFanOutExecutor;

    @Value("${github.repository-allowlist:}")
    private String repositoryAllowlist;

    public PRReviewAgent(GitHubDiffTool gitHubDiffTool,
                         SecurityScanTool securityScanTool,
                         ArchitectureCheckTool architectureCheckTool,
                         PerformanceAnalysisTool performanceAnalysisTool,
                         LLMReviewTool llmReviewTool,
                         GitHubCommentTool gitHubCommentTool,
                         JiraTicketTool jiraTicketTool,
                         ReviewHistoryTool reviewHistoryTool,
                         GitHubAutoFixTool gitHubAutoFixTool,
                         @Qualifier("reviewFanOutExecutor") Executor reviewFanOutExecutor) {
        this.gitHubDiffTool = gitHubDiffTool;
        this.securityScanTool = securityScanTool;
        this.architectureCheckTool = architectureCheckTool;
        this.performanceAnalysisTool = performanceAnalysisTool;
        this.llmReviewTool = llmReviewTool;
        this.gitHubCommentTool = gitHubCommentTool;
        this.jiraTicketTool = jiraTicketTool;
        this.reviewHistoryTool = reviewHistoryTool;
        this.gitHubAutoFixTool = gitHubAutoFixTool;
        this.reviewFanOutExecutor = reviewFanOutExecutor;
    }

    @Async("reviewExecutor")
    public CompletableFuture<ReviewResult> review(String repoFullName, int prNumber,
                                                   String headBranch, String headSha,
                                                   String prTitle, String prBody) {
        String safeRepoFullName = sanitizeForLog(repoFullName);
        log.info("Starting PR review - {}/#{} at {}", safeRepoFullName, prNumber, headSha);
        try {
            if (!allowedRepositories().contains(repoFullName)) {
                throw new ReviewAgentException("Repository is not in the configured allowlist");
            }
            String diff = gitHubDiffTool.fetchDiff(repoFullName, prNumber, headSha);
            if (diff == null || diff.isBlank()) {
                throw new ReviewAgentException("GitHub returned an empty diff; refusing to approve");
            }
            List<DiffFile> changedFiles = gitHubDiffTool.parseDiff(diff);
            if (changedFiles.isEmpty()) {
                throw new ReviewAgentException("No reviewable files were parsed from the pull request diff");
            }

            CompletableFuture<Optional<TicketAlignment>> jiraFuture = supply(
                    () -> jiraTicketTool.checkAlignment(prTitle, prBody, diff));
            CompletableFuture<String> patternsFuture = supply(
                    () -> reviewHistoryTool.loadTeamPatterns(repoFullName));
            CompletableFuture<List<ReviewComment>> securityFuture = supply(
                    () -> securityScanTool.scan(changedFiles));
            CompletableFuture<List<ReviewComment>> architectureFuture = supply(
                    () -> architectureCheckTool.check(changedFiles));
            CompletableFuture<List<ReviewComment>> performanceFuture = supply(
                    () -> performanceAnalysisTool.analyse(changedFiles));

            CompletableFuture.allOf(jiraFuture, patternsFuture, securityFuture,
                    architectureFuture, performanceFuture).join();

            List<ReviewComment> staticComments = new ArrayList<>();
            staticComments.addAll(securityFuture.join());
            staticComments.addAll(architectureFuture.join());
            staticComments.addAll(performanceFuture.join());
            String teamPatterns = patternsFuture.join();

            List<CompletableFuture<ReviewRoundResult>> specialistFutures = SPECIALIST_PASSES.stream()
                    .map(pass -> supply(() -> llmReviewTool.review(
                            pass, changedFiles, staticComments, teamPatterns)))
                    .toList();
            CompletableFuture.allOf(specialistFutures.toArray(CompletableFuture[]::new)).join();

            List<ReviewRoundResult> rounds = new ArrayList<>();
            specialistFutures.forEach(future -> rounds.add(future.join()));

            List<ReviewComment> specialistComments = rounds.stream()
                    .flatMap(round -> round.getComments().stream())
                    .toList();
            AdversarialVerificationResult verification = llmReviewTool.verify(
                    changedFiles, deduplicate(specialistComments), teamPatterns);
            rounds.add(verification.getRound());

            List<ReviewComment> allComments = new ArrayList<>(staticComments);
            allComments.addAll(verification.getConfirmedComments());
            allComments.addAll(verification.getRound().getComments());
            allComments = deduplicate(allComments);

            boolean reviewComplete = rounds.stream()
                    .allMatch(round -> round.getStatus() == ReviewRoundResult.RoundStatus.COMPLETE);
            ReviewResult.ReviewVerdict verdict = determineVerdict(allComments, reviewComplete);
            int score = calculateScore(allComments);
            List<AutoFix> autoFixes = List.of();
            if (reviewComplete) {
                gitHubAutoFixTool.authorizeEligible(allComments);
                autoFixes = gitHubAutoFixTool.applyFixes(repoFullName, headBranch, allComments);
            }

            ReviewResult result = ReviewResult.builder()
                    .repositoryFullName(repoFullName)
                    .pullRequestNumber(prNumber)
                    .verdict(verdict)
                    .overallScore(score)
                    .comments(allComments)
                    .securitySummary(buildSecuritySummary(allComments))
                    .executiveSummary(llmReviewTool.generateSummary(allComments, verdict, score))
                    .ticketAlignment(jiraFuture.join().orElse(null))
                    .autoFixesApplied(autoFixes)
                    .reviewRounds(List.copyOf(rounds))
                    .reviewComplete(reviewComplete)
                    .reviewedAt(Instant.now())
                    .build();

            // Before publishing, verify the PR head has not moved since the webhook
            // was received. An approval against a stale diff would be recorded on the
            // current head if commit_id is absent, silently approving unseen changes.
            if (headSha != null && !headSha.isBlank()) {
                String currentSha = gitHubDiffTool.fetchCurrentHeadSha(repoFullName, prNumber);
                if (!headSha.equals(currentSha)) {
                    log.warn("PR head moved since webhook for {}/#{}: reviewed={} current={}",
                            safeRepoFullName, prNumber, headSha, currentSha);
                    throw new ReviewAgentException(
                            "PR head changed since review started; aborting to avoid stale verdict");
                }
            }

            gitHubCommentTool.postReview(repoFullName, prNumber, headSha, result);
            log.info("Review posted - verdict={} score={} complete={}", verdict, score, reviewComplete);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("PR review failed for {}/#{}: {}", safeRepoFullName, prNumber, e.getMessage(), e);
            throw e instanceof ReviewAgentException reviewException
                    ? reviewException
                    : new ReviewAgentException("PR review failed", e);
        }
    }

    private String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    private <T> CompletableFuture<T> supply(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, reviewFanOutExecutor);
    }

    private ReviewResult.ReviewVerdict determineVerdict(List<ReviewComment> comments,
                                                         boolean reviewComplete) {
        boolean hasBlocking = comments.stream().anyMatch(c ->
                c.getSeverity() == ReviewComment.CommentSeverity.CRITICAL
                        || c.getSeverity() == ReviewComment.CommentSeverity.HIGH);
        if (hasBlocking) {
            return ReviewResult.ReviewVerdict.REQUEST_CHANGES;
        }
        if (!reviewComplete) {
            return ReviewResult.ReviewVerdict.COMMENT;
        }
        boolean hasAdvisory = comments.stream().anyMatch(c ->
                c.getSeverity() == ReviewComment.CommentSeverity.MEDIUM
                        || c.getSeverity() == ReviewComment.CommentSeverity.LOW);
        return hasAdvisory ? ReviewResult.ReviewVerdict.COMMENT : ReviewResult.ReviewVerdict.APPROVE;
    }

    private int calculateScore(List<ReviewComment> comments) {
        int deductions = comments.stream().mapToInt(c -> switch (c.getSeverity()) {
            case CRITICAL -> 25;
            case HIGH -> 15;
            case MEDIUM -> 5;
            case LOW -> 2;
            case INFO -> 0;
        }).sum();
        return Math.max(0, 100 - deductions);
    }

    private List<ReviewComment> deduplicate(List<ReviewComment> comments) {
        Map<String, ReviewComment> unique = new LinkedHashMap<>();
        for (ReviewComment comment : comments) {
            String key = (comment.getFilename() + "|" + comment.getLineNumber() + "|"
                    + comment.getCategory() + "|" + comment.getTitle()).toLowerCase(Locale.ROOT);
            unique.merge(key, comment, this::moreSevere);
        }
        return List.copyOf(unique.values());
    }

    private ReviewComment moreSevere(ReviewComment first, ReviewComment second) {
        return first.getSeverity().ordinal() <= second.getSeverity().ordinal()
                ? first : second;
    }

    private ReviewResult.SecuritySummary buildSecuritySummary(List<ReviewComment> comments) {
        List<ReviewComment> securityComments = comments.stream()
                .filter(c -> c.getCategory() == ReviewComment.CommentCategory.SECURITY)
                .toList();
        return ReviewResult.SecuritySummary.builder()
                .criticalCount(countSeverity(securityComments, ReviewComment.CommentSeverity.CRITICAL))
                .highCount(countSeverity(securityComments, ReviewComment.CommentSeverity.HIGH))
                .mediumCount(countSeverity(securityComments, ReviewComment.CommentSeverity.MEDIUM))
                .build();
    }

    private int countSeverity(List<ReviewComment> comments, ReviewComment.CommentSeverity severity) {
        return (int) comments.stream().filter(c -> c.getSeverity() == severity).count();
    }

    private java.util.Set<String> allowedRepositories() {
        if (repositoryAllowlist == null || repositoryAllowlist.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(repositoryAllowlist.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
