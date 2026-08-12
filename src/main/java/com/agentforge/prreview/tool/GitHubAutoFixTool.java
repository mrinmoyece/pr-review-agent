package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.ReviewComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Identifies low-risk findings that may be suitable for a human-approved fix.
 * Autonomous model-generated repository writes are intentionally prohibited.
 */
@Component
@Slf4j
public class GitHubAutoFixTool {

    @Value("${review.auto-fix.enabled:false}")
    private boolean autoFixEnabled;

    /**
     * Returns human-action-required records for eligible findings.
     */
    public List<AutoFix> applyFixes(String repoFullName, String branchRef,
                                     List<ReviewComment> comments) {
        if (!autoFixEnabled) {
            log.info("Auto-fix is disabled; review findings require human action");
            return List.of();
        }
        if (branchRef == null || branchRef.isBlank()) {
            log.warn("Auto-fix skipped because the PR head branch is unavailable");
            return List.of();
        }
        List<ReviewComment> eligible = comments.stream()
                .filter(c -> c.isAutoFixable()
                        && (c.getSeverity() == ReviewComment.CommentSeverity.LOW
                            || c.getSeverity() == ReviewComment.CommentSeverity.MEDIUM)
                        && c.getFilename() != null
                        && isAllowedSourcePath(c.getFilename()))
                .toList();

        if (eligible.isEmpty()) {
            log.info("No auto-fixable comments for {}", repoFullName);
            return List.of();
        }

        log.info("{} findings require human-approved fixes on branch {} of {}",
                eligible.size(), branchRef, repoFullName);
        Map<String, List<ReviewComment>> byFile = new LinkedHashMap<>();
        for (ReviewComment c : eligible) {
            byFile.computeIfAbsent(c.getFilename(), key -> new ArrayList<>()).add(c);
        }
        return byFile.keySet().stream()
                .map(filename -> AutoFix.skipped(filename,
                        "Autonomous repository writes are prohibited; human approval required"))
                .toList();
    }

    /**
     * Applies the trusted write policy after review verification. Model-provided
     * auto-fix flags and snippets are never used as authorization.
     */
    public void authorizeEligible(List<ReviewComment> comments) {
        for (ReviewComment comment : comments) {
            boolean eligible = comment.getSeverity() == ReviewComment.CommentSeverity.LOW
                    && comment.getCategory() == ReviewComment.CommentCategory.STYLE
                    && comment.getLineNumber() != null
                    && comment.getFilename() != null
                    && isAllowedSourcePath(comment.getFilename());
            comment.setAutoFixable(eligible);
            comment.setSuggestedFix(null);
        }
    }

    private boolean isAllowedSourcePath(String filename) {
        String normalized = filename.replace('\\', '/');
        return !normalized.startsWith("/")
                && !normalized.contains("../")
                && (normalized.startsWith("src/main/java/")
                    || normalized.startsWith("src/test/java/"))
                && normalized.endsWith(".java");
    }

}
