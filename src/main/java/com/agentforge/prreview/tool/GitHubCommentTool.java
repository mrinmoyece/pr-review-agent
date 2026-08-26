package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.model.ReviewRoundResult;
import com.agentforge.prreview.model.TicketAlignment;
import com.agentforge.prreview.security.GitHubCredentialProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Posts the review result back to GitHub as a proper PR review with inline comments.
 *
 * <p>Uses GitHub REST API v3 — pull_request_review endpoint for the review body
 * and review_comments for inline file annotations.
 *
 * <p><strong>Security:</strong> all content derived from model output or the diff
 * (finding titles, bodies, filenames, round detail, ticket fields) is treated as
 * untrusted and passed through {@link #safeText} / {@link #safeTitle} before being
 * inserted into the report. This prevents {@code </details>} tag injection, GitHub
 * alert-callout spoofing, and HTML metacharacter injection inside the Markdown body.
 */
@Component
@Slf4j
public class GitHubCommentTool {

    private final RestClient gitHubRestClient;
    private final GitHubCredentialProvider credentialProvider;

    /** Canonical URL of this agent's source repository, used for report attribution. */
    private final String agentRepositoryUrl;

    public GitHubCommentTool(
            RestClient gitHubRestClient,
            GitHubCredentialProvider credentialProvider,
            @Value("${review.agent.repository-url:https://github.com/mrinmoyece/pr-review-agent}")
            String agentRepositoryUrl) {
        this.gitHubRestClient = gitHubRestClient;
        this.credentialProvider = credentialProvider;
        this.agentRepositoryUrl = agentRepositoryUrl;
    }

    @CircuitBreaker(name = "github", fallbackMethod = "postReviewFallback")
    public void postReview(String repoFullName, int prNumber, String headSha, ReviewResult result) {
        log.info("Posting review to {}/#{} — verdict={} comments={}",
                repoFullName, prNumber, result.getVerdict(), result.getComments().size());

        String event = switch (result.getVerdict()) {
            case APPROVE          -> "APPROVE";
            case REQUEST_CHANGES  -> "REQUEST_CHANGES";
            case COMMENT          -> "COMMENT";
        };

        Map<String, Object> body = new HashMap<>();
        body.put("body", buildReviewBody(result));
        body.put("event", event);
        body.put("comments", buildInlineComments(result.getComments()));
        if (headSha != null && !headSha.isBlank()) {
            body.put("commit_id", headSha);
        }

        gitHubRestClient.post()
                .uri("/repos/{repo}/pulls/{pr}/reviews", repoFullName, prNumber)
                .header("Authorization", "Bearer " + credentialProvider.token())
                .header("Accept", "application/vnd.github.v3+json")
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Review posted successfully to {}/#{}", repoFullName, prNumber);
    }

    public void postReviewFallback(String repoFullName, int prNumber, String headSha,
                                   ReviewResult result, Throwable t) {
        throw new IllegalStateException(
                "Failed to post review to GitHub " + repoFullName + "/#" + prNumber, t);
    }

    String buildReviewBody(ReviewResult result) {
        StringBuilder sb = new StringBuilder();

        // ── Header ──────────────────────────────────────────────────────────
        String verdictBadge = switch (result.getVerdict()) {
            case APPROVE         -> "✅ APPROVED";
            case REQUEST_CHANGES -> "❌ CHANGES REQUESTED";
            case COMMENT         -> "💬 COMMENT";
        };
        int score = result.getOverallScore();
        String scoreLabel = score >= 90 ? "🟢" : score >= 70 ? "🟡" : score >= 50 ? "🟠" : "🔴";

        sb.append("# 🤖 AgentForge Enterprise PR Review\n\n");

        // Incomplete-coverage caution — always at top, highest visibility
        if (!result.isReviewComplete()) {
            sb.append("> [!CAUTION]\n");
            sb.append("> **Incomplete review** — one or more specialist rounds did not complete.");
            sb.append(" This result **must not** be treated as an approval.\n\n");
        }

        sb.append("| | |\n|---|---|\n");
        sb.append("| **Verdict** | ").append(verdictBadge).append(" |\n");
        sb.append("| **Quality Score** | ").append(scoreLabel).append(" ")
                .append(scoreBar(score)).append(" **").append(score).append(" / 100** |\n");
        if (result.getReviewedAt() != null) {
            sb.append("| **Reviewed at** | `").append(result.getReviewedAt()).append("` |\n");
        }
        if (result.getRepositoryFullName() != null) {
            sb.append("| **Repository** | `").append(result.getRepositoryFullName());
            if (result.getPullRequestNumber() > 0) {
                sb.append("` · **PR** `#").append(result.getPullRequestNumber());
            }
            sb.append("` |\n");
        }
        sb.append("\n---\n\n");

        // ── Executive Summary ────────────────────────────────────────────────
        if (result.getExecutiveSummary() != null && !result.getExecutiveSummary().isBlank()) {
            sb.append("## 📋 Executive Summary\n\n");
            // Executive summary is LLM-generated; escape HTML to prevent spoofing
            sb.append("> ").append(safeText(result.getExecutiveSummary()).replace("\n", "\n> "))
                    .append("\n\n");
        }

        // ── Security Scorecard ───────────────────────────────────────────────
        ReviewResult.SecuritySummary sec = result.getSecuritySummary();
        if (sec != null && (sec.getCriticalCount() + sec.getHighCount() + sec.getMediumCount()) > 0) {
            sb.append("## 🔒 Security Scorecard\n\n");
            sb.append("| Severity | Count | Merge guidance |\n");
            sb.append("|---|---:|---|\n");
            if (sec.getCriticalCount() > 0)
                sb.append("| 🔴 **Critical** | ").append(sec.getCriticalCount())
                        .append(" | Must fix before merge |\n");
            if (sec.getHighCount() > 0)
                sb.append("| 🟠 **High** | ").append(sec.getHighCount())
                        .append(" | Should fix before merge |\n");
            if (sec.getMediumCount() > 0)
                sb.append("| 🟡 **Medium** | ").append(sec.getMediumCount())
                        .append(" | Address in follow-up |\n");
            sb.append("\n");
        }

        // ── Findings by category (collapsible) ─────────────────────────────
        Map<ReviewComment.CommentCategory, List<ReviewComment>> byCategory = result.getComments()
                .stream()
                .filter(c -> c.getLineNumber() == null)
                .collect(Collectors.groupingBy(
                        c -> c.getCategory() != null
                                ? c.getCategory()
                                : ReviewComment.CommentCategory.CORRECTNESS));

        if (!byCategory.isEmpty()) {
            sb.append("## 🔍 Findings\n\n");
            List<ReviewComment.CommentCategory> categoryOrder = List.of(
                    ReviewComment.CommentCategory.SECURITY,
                    ReviewComment.CommentCategory.CORRECTNESS,
                    ReviewComment.CommentCategory.PERFORMANCE,
                    ReviewComment.CommentCategory.ARCHITECTURE,
                    ReviewComment.CommentCategory.TEST_COVERAGE,
                    ReviewComment.CommentCategory.STYLE
            );
            for (ReviewComment.CommentCategory cat : categoryOrder) {
                List<ReviewComment> catComments = byCategory.get(cat);
                if (catComments == null || catComments.isEmpty()) continue;
                appendCategorySection(sb, cat, catComments);
            }
            byCategory.forEach((cat, catComments) -> {
                if (!categoryOrder.contains(cat) && !catComments.isEmpty()) {
                    appendCategorySection(sb, cat, catComments);
                }
            });
        }

        // ── Specialist Round Coverage ─────────────────────────────────────
        if (result.getReviewRounds() != null && !result.getReviewRounds().isEmpty()) {
            sb.append("<details>\n<summary>🧪 <strong>Multi-Agent Review Coverage</strong> — ")
                    .append(result.getReviewRounds().size()).append(" round")
                    .append(result.getReviewRounds().size() != 1 ? "s" : "")
                    .append("</summary>\n\n");
            sb.append("| Specialist | Status | Model | Chunks | Findings |\n");
            sb.append("|---|:---:|---|---:|---:|\n");
            for (ReviewRoundResult round : result.getReviewRounds()) {
                String statusIcon = switch (round.getStatus()) {
                    case COMPLETE   -> "✅";
                    case TRUNCATED  -> "⚠️";
                    case FAILED     -> "❌";
                    default         -> "❓";
                };
                // round.getModel() is a trusted config value; round.getDetail() is LLM output — escape
                sb.append("| ").append(passIcon(round.getPass())).append(" ")
                        .append(round.getPass() != null ? round.getPass().name() : "—")
                        .append(" | ").append(statusIcon)
                        .append(" | `").append(escapeTableCell(round.getModel())).append("`")
                        .append(" | ").append(round.getChunksReviewed())
                        .append(" | ")
                        .append(round.getComments() != null ? round.getComments().size() : 0)
                        .append(" |\n");
                if (round.getDetail() != null && !round.getDetail().isBlank()) {
                    sb.append("|  | | *").append(escapeTableCell(safeText(round.getDetail())))
                            .append("* | | |\n");
                }
            }
            sb.append("\n</details>\n\n");
        }

        // ── Jira Ticket Alignment ─────────────────────────────────────────
        TicketAlignment ta = result.getTicketAlignment();
        if (ta != null) {
            String taIcon = ta.getAlignmentScore() >= 80 ? "✅"
                    : ta.getAlignmentScore() >= 50 ? "⚠️" : "❌";
            // Ticket key is a structured JIRA key — treat as untrusted (could be injected)
            String safeKey = safeText(ta.getTicketKey());
            sb.append("<details>\n<summary>🎫 <strong>Jira Alignment</strong> — ")
                    .append(taIcon).append(" ").append(safeTitle(ta.getTicketKey()))
                    .append(" · Score: ").append(ta.getAlignmentScore()).append("/100</summary>\n\n");
            sb.append("| | |\n|---|---|\n");
            sb.append("| **Ticket** | `").append(safeKey).append("` |\n");
            sb.append("| **Verdict** | ").append(ta.getVerdict()).append(" |\n");
            sb.append("| **Alignment Score** | ").append(scoreBar(ta.getAlignmentScore()))
                    .append(" ").append(ta.getAlignmentScore()).append("/100 |\n\n");
            if (ta.getSummary() != null && !ta.getSummary().isBlank()) {
                sb.append(safeText(ta.getSummary())).append("\n\n");
            }
            if (ta.getMissingRequirements() != null && !ta.getMissingRequirements().isEmpty()) {
                sb.append("**Missing requirements:**\n");
                ta.getMissingRequirements().forEach(r ->
                        sb.append("- ").append(safeText(r)).append("\n"));
                sb.append("\n");
            }
            if (ta.getUnrequiredChanges() != null && !ta.getUnrequiredChanges().isEmpty()) {
                sb.append("**Unrelated changes:**\n");
                ta.getUnrequiredChanges().forEach(c ->
                        sb.append("- ").append(safeText(c)).append("\n"));
                sb.append("\n");
            }
            sb.append("</details>\n\n");
        }

        // ── Human-Approved Fix Candidates ────────────────────────────────
        List<AutoFix> fixes = result.getAutoFixesApplied();
        if (fixes != null && !fixes.isEmpty()) {
            sb.append("<details>\n<summary>🔧 <strong>Fix Candidates</strong> — ")
                    .append(fixes.size()).append(" awaiting human review</summary>\n\n");
            fixes.forEach(f -> {
                sb.append("**`").append(safeText(f.getFilename())).append("`**\n\n");
                if (f.getSkipReason() != null && !f.getSkipReason().isBlank()) {
                    sb.append("> ").append(safeText(f.getSkipReason())).append("\n\n");
                }
            });
            sb.append("</details>\n\n");
        }

        // ── Footer ── uses agentRepositoryUrl, not the reviewed repo ─────
        sb.append("---\n\n");
        sb.append("*🤖 Generated by [AgentForge PR Review Agent](").append(agentRepositoryUrl)
                .append(") · Multi-agent parallel review: Security · Logic · Architecture")
                .append(" · Performance · Testing*\n");

        return sb.toString();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void appendCategorySection(StringBuilder sb,
                                        ReviewComment.CommentCategory cat,
                                        List<ReviewComment> catComments) {
        long critical = catComments.stream()
                .filter(c -> c.getSeverity() == ReviewComment.CommentSeverity.CRITICAL).count();
        long high = catComments.stream()
                .filter(c -> c.getSeverity() == ReviewComment.CommentSeverity.HIGH).count();
        String badge = critical > 0 ? " 🔴 " + critical + " critical"
                : high > 0 ? " 🟠 " + high + " high" : "";

        // safeTitle used in <summary> HTML context
        sb.append("<details>\n<summary>").append(categoryIcon(cat)).append(" <strong>")
                .append(safeTitle(formatCategoryName(cat))).append("</strong>")
                .append(badge).append(" — ").append(catComments.size()).append(" finding")
                .append(catComments.size() != 1 ? "s" : "").append("</summary>\n\n");

        catComments.stream()
                .sorted(Comparator.comparingInt(
                        c -> c.getSeverity() != null ? c.getSeverity().ordinal() : 99))
                .forEach(c -> {
                    // title and filename are untrusted (from model/diff) — safeText
                    sb.append("#### ").append(severityLabel(c.getSeverity()))
                            .append(" ").append(safeText(c.getTitle())).append("\n\n");
                    if (c.getFilename() != null)
                        sb.append("**File:** `").append(safeText(c.getFilename())).append("`\n\n");
                    sb.append(safeText(c.getBody())).append("\n\n");
                    // suggestedFix is always null in production (cleared by validateAndDeduplicate
                    // and authorizeEligible); rendering path deliberately omitted.
                });
        sb.append("</details>\n\n");
    }

    /**
     * Escapes untrusted text (model output, diff content) for safe inclusion in the
     * Markdown report. Prevents {@code </details>} tag injection, HTML metacharacter
     * injection, and GitHub alert-callout spoofing ({@code > [!CAUTION]}).
     */
    static String safeText(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                // Prevent alert spoofing: "> [!CAUTION]" or "> [!WARNING]" etc.
                .replace("[!", "\\[!");
    }

    /**
     * Escapes untrusted text for use inside HTML element content (e.g. {@code <summary>}).
     * Stricter than {@link #safeText}: also encodes double quotes and apostrophes.
     */
    static String safeTitle(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Renders a 10-block progress bar for a 0–100 score. */
    private static String scoreBar(int score) {
        int filled = Math.max(0, Math.min(10, score / 10));
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String severityLabel(ReviewComment.CommentSeverity severity) {
        if (severity == null) return "ℹ️ INFO";
        return switch (severity) {
            case CRITICAL -> "🔴 CRITICAL";
            case HIGH     -> "🟠 HIGH";
            case MEDIUM   -> "🟡 MEDIUM";
            case LOW      -> "🟢 LOW";
            default       -> "ℹ️ INFO";
        };
    }

    private static String categoryIcon(ReviewComment.CommentCategory category) {
        if (category == null) return "🔍";
        return switch (category) {
            case SECURITY      -> "🔒";
            case CORRECTNESS   -> "🐛";
            case PERFORMANCE   -> "⚡";
            case ARCHITECTURE  -> "🏗️";
            case TEST_COVERAGE -> "🧪";
            case STYLE         -> "✨";
        };
    }

    private static String formatCategoryName(ReviewComment.CommentCategory category) {
        if (category == null) return "Other";
        return switch (category) {
            case SECURITY      -> "Security";
            case CORRECTNESS   -> "Correctness";
            case PERFORMANCE   -> "Performance";
            case ARCHITECTURE  -> "Architecture";
            case TEST_COVERAGE -> "Test Coverage";
            case STYLE         -> "Style";
        };
    }

    private static String passIcon(com.agentforge.prreview.model.ReviewPass pass) {
        if (pass == null) return "🔍";
        return switch (pass) {
            case SECURITY     -> "🔒";
            case TESTING      -> "🧪";
            case ARCHITECTURE -> "🏗️";
            case PERFORMANCE  -> "⚡";
            default           -> "🔍";
        };
    }

    private static String escapeTableCell(String value) {
        if (value == null) return "";
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

    /**
     * Formats an inline diff comment. Title, category and body are untrusted —
     * all passed through {@link #safeText}.
     */
    private String formatComment(ReviewComment c) {
        return categoryIcon(c.getCategory()) + " " + severityLabel(c.getSeverity())
                + " **" + safeText(c.getTitle()) + "**\n\n"
                + safeText(c.getBody());
        // suggestedFix deliberately omitted — always null in production.
    }
}
