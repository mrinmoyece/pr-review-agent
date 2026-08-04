package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Performance Analysis Tool — detects common performance anti-patterns via AST-level regex.
 *
 * Rules implemented:
 *  - N+1 query detection (DB call inside loop)
 *  - Synchronised block overuse
 *  - Unbounded collection operations
 *  - Missing pagination on list queries
 *  - String concatenation in loops (should use StringBuilder)
 *  - Thread.sleep in production paths
 */
@Component
@Slf4j
public class PerformanceAnalysisTool {

    private record PerformanceRule(String name, Pattern pattern, String explanation,
                                   ReviewComment.CommentSeverity severity) {}

    private static final List<PerformanceRule> RULES = List.of(
        new PerformanceRule(
            "N+1 Query",
            Pattern.compile("(?s)for\\s*\\(.*\\)\\s*\\{[^}]*(repository|dao|jdbcTemplate|findBy|select)"),
            "Potential N+1 query detected. Consider using a JOIN fetch, batch load, or @EntityGraph " +
            "to retrieve related entities in a single query.",
            ReviewComment.CommentSeverity.HIGH
        ),
        new PerformanceRule(
            "Synchronised overuse",
            Pattern.compile("synchronized\\s*\\(this\\)"),
            "Synchronising on `this` can cause unnecessary contention. " +
            "Prefer a private final lock object or java.util.concurrent primitives (ReentrantLock, ConcurrentHashMap).",
            ReviewComment.CommentSeverity.MEDIUM
        ),
        new PerformanceRule(
            "String concat in loop",
            Pattern.compile("(?s)for\\s*\\(.*\\)\\s*\\{[^}]*\\+=[^}]*String"),
            "String concatenation inside a loop creates O(n²) object churn. " +
            "Use StringBuilder.append() instead.",
            ReviewComment.CommentSeverity.MEDIUM
        ),
        new PerformanceRule(
            "Thread.sleep in production",
            Pattern.compile("Thread\\.sleep\\s*\\("),
            "Thread.sleep() blocks a platform thread. " +
            "In Spring Boot 3.2+ (virtual threads), prefer ScheduledExecutorService or @Scheduled. " +
            "If testing, mock the clock instead.",
            ReviewComment.CommentSeverity.MEDIUM
        ),
        new PerformanceRule(
            "Unbounded findAll",
            Pattern.compile("findAll\\s*\\(\\s*\\)"),
            "Unbounded findAll() on a large table will cause OOM. " +
            "Add Pageable parameter: findAll(Pageable pageable).",
            ReviewComment.CommentSeverity.HIGH
        ),
        new PerformanceRule(
            "Missing index hint on large collection",
            Pattern.compile("@OneToMany.*fetch\\s*=\\s*FetchType\\.EAGER"),
            "EAGER fetch on @OneToMany can load thousands of records unexpectedly. " +
            "Use LAZY fetch and load explicitly when needed.",
            ReviewComment.CommentSeverity.MEDIUM
        )
    );

    public List<ReviewComment> analyse(List<DiffFile> changedFiles) {
        List<ReviewComment> comments = new ArrayList<>();

        for (DiffFile file : changedFiles) {
            if (!List.of("java", "kotlin").contains(file.getLanguage())) continue;

            String addedContent = String.join("\n", file.getAddedLines());
            for (PerformanceRule rule : RULES) {
                if (rule.pattern().matcher(addedContent).find()) {
                    comments.add(ReviewComment.builder()
                            .filename(file.getFilename())
                            .category(ReviewComment.CommentCategory.PERFORMANCE)
                            .severity(rule.severity())
                            .title(rule.name())
                            .body(rule.explanation())
                            .autoFixable(false)
                            .build());
                    log.debug("Performance issue '{}' in {}", rule.name(), file.getFilename());
                }
            }
        }

        log.info("Performance analysis complete: {} issues across {} files",
                comments.size(), changedFiles.size());
        return comments;
    }
}
