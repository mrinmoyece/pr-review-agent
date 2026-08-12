package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.model.ReviewRoundResult;
import com.agentforge.prreview.model.TicketAlignment;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts the review result back to GitHub as a proper PR review with inline comments.
 *
 * Uses GitHub REST API v3 — pull_request_review endpoint for the review body
 * and review_comments for inline file annotations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubCommentTool {

    private final RestClient gitHubRestClient;

    @Value("${github.token}")
    private String githubToken;

    @Retry(name = "github")
    @CircuitBreaker(name = "github", fallbackMethod = "postReviewFallback")
    public void postReview(String repoFullName, int prNumber, ReviewResult result) {
        log.info("Posting review to {}/#{} — verdict={} comments={}",
                repoFullName, prNumber, result.getVerdict(), result.getComments().size());

        // Build GitHub review event
        String event = switch (result.getVerdict()) {
            case APPROVE          -> "APPROVE";
            case REQUEST_CHANGES  -> "REQUEST_CHANGES";
            case COMMENT          -> "COMMENT";
        };

        Map<String, Object> body = new HashMap<>();
        body.put("body", buildReviewBody(result));
        body.put("event", event);
        body.put("comments", buildInlineComments(result.getComments()));

        gitHubRestClient.post()
                .uri("/repos/{repo}/pulls/{pr}/reviews", repoFullName, prNumber)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Review posted successfully to {}/#{}", repoFullName, prNumber);
    }

    public void postReviewFallback(String repoFullName, int prNumber,
                                   ReviewResult result, Throwable t) {
        throw new IllegalStateException(
                "Failed to post review to GitHub " + repoFullName + "/#" + prNumber, t);
    }

    String buildReviewBody(ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## AgentForge PR Review\n\n");
        sb.append("**Verdict**: ").append(result.getVerdict()).append("\n");
        sb.append("**Score**: ").append(result.getOverallScore()).append("/100\n\n");
        if (!result.isReviewComplete()) {
            sb.append("> [!WARNING]\n");
            sb.append("> Review coverage is incomplete. This result must not be treated as approval.\n\n");
        }

        if (result.getReviewRounds() != null && !result.getReviewRounds().isEmpty()) {
            sb.append("### Review Coverage\n\n");
            sb.append("| Pass | Status | Model | Chunks | Findings | Detail |\n");
            sb.append("|---|---|---|---:|---:|---|\n");
            for (ReviewRoundResult round : result.getReviewRounds()) {
                sb.append("| ").append(round.getPass())
                        .append(" | ").append(round.getStatus())
                        .append(" | `").append(escapeTableCell(round.getModel())).append("`")
                        .append(" | ").append(round.getChunksReviewed())
                        .append(" | ").append(round.getComments().size())
                        .append(" | ").append(escapeTableCell(round.getDetail())).append(" |\n");
            }
            sb.append("\n");
        }

        if (result.getSecuritySummary() != null) {
            ReviewResult.SecuritySummary sec = result.getSecuritySummary();
            sb.append("### Security Summary\n");
            if (sec.getCriticalCount() > 0) sb.append("🔴 Critical: ").append(sec.getCriticalCount()).append("\n");
            if (sec.getHighCount() > 0)     sb.append("🟠 High: ").append(sec.getHighCount()).append("\n");
            if (sec.getMediumCount() > 0)   sb.append("🟡 Medium: ").append(sec.getMediumCount()).append("\n");
            sb.append("\n");
        }

        if (result.getExecutiveSummary() != null) {
            sb.append("### Executive Summary\n").append(result.getExecutiveSummary()).append("\n\n");
        }

        List<ReviewComment> generalComments = result.getComments().stream()
                .filter(comment -> comment.getLineNumber() == null)
                .toList();
        if (!generalComments.isEmpty()) {
            sb.append("### General Findings\n");
            generalComments.forEach(comment -> sb.append("- **[")
                    .append(comment.getSeverity()).append("] ")
                    .append(comment.getTitle()).append("** (`")
                    .append(comment.getFilename()).append("`): ")
                    .append(comment.getBody()).append("\n"));
            sb.append("\n");
        }

        // Ticket alignment section
        TicketAlignment ta = result.getTicketAlignment();
        if (ta != null) {
            sb.append("### Jira Ticket Alignment\n");
            sb.append("**Ticket**: ").append(ta.getTicketKey()).append(" | ");
            sb.append("**Verdict**: ").append(ta.getVerdict()).append(" | ");
            sb.append("**Score**: ").append(ta.getAlignmentScore()).append("/100\n");
            if (ta.getSummary() != null && !ta.getSummary().isBlank()) {
                sb.append(ta.getSummary()).append("\n");
            }
            if (ta.getMissingRequirements() != null && !ta.getMissingRequirements().isEmpty()) {
                sb.append("\n**Missing requirements:**\n");
                ta.getMissingRequirements().forEach(r -> sb.append("- ").append(r).append("\n"));
            }
            if (ta.getUnrequiredChanges() != null && !ta.getUnrequiredChanges().isEmpty()) {
                sb.append("\n**Unrelated changes:**\n");
                ta.getUnrequiredChanges().forEach(c -> sb.append("- ").append(c).append("\n"));
            }
            sb.append("\n");
        }

        // Auto-fixes section
        List<AutoFix> fixes = result.getAutoFixesApplied();
        if (fixes != null && !fixes.isEmpty()) {
            sb.append("### Human-Approved Fix Candidates\n");
            fixes.forEach(f ->
                sb.append("- `").append(f.getFilename()).append("` — ")
                  .append(f.getSkipReason()).append("\n")
            );
            sb.append("\n");
        }

        return sb.toString();
    }

    private String escapeTableCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", "<br>");
    }

    private List<Map<String, Object>> buildInlineComments(List<ReviewComment> comments) {
        return comments.stream()
                .filter(c -> c.getLineNumber() != null)
                .map(c -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("path", c.getFilename());
                    map.put("line", c.getLineNumber());
                    map.put("body", formatComment(c));
                    return map;
                })
                .toList();
    }

    private String formatComment(ReviewComment c) {
        String icon = switch (c.getSeverity()) {
            case CRITICAL -> "🔴";
            case HIGH     -> "🟠";
            case MEDIUM   -> "🟡";
            case LOW      -> "🟢";
            default       -> "ℹ️";
        };
        return String.format("%s **[%s] %s**\n\n%s", icon, c.getCategory(), c.getTitle(), c.getBody());
    }
}
