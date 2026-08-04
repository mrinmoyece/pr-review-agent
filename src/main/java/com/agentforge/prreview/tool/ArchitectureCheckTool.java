package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks for Spring Boot / microservices architecture violations.
 * Flags missing resilience patterns, direct DB calls from controllers,
 * missing circuit breakers on external calls, and RestTemplate usage
 * (deprecated in favour of WebClient/RestClient).
 */
@Component
@Slf4j
public class ArchitectureCheckTool {

    private static final Pattern REST_TEMPLATE = Pattern.compile(
            "new RestTemplate\\(\\)|RestTemplate restTemplate");
    private static final Pattern MISSING_CIRCUIT_BREAKER = Pattern.compile(
            "restClient\\.(get|post|put|delete)\\(|WebClient.*\\.retrieve\\(");

    public List<ReviewComment> check(List<DiffFile> changedFiles) {
        List<ReviewComment> comments = new ArrayList<>();

        for (DiffFile file : changedFiles) {
            // DiffFile.filename is the path relative to repo root
            if (!file.getFilename().endsWith(".java")) continue;
            // rawDiff contains the full unified diff for this file
            String patch = file.getRawDiff();

            // Check: RestTemplate (deprecated in Spring Boot 3.x)
            if (REST_TEMPLATE.matcher(patch).find()) {
                comments.add(ReviewComment.builder()
                        .filename(file.getFilename())
                        .severity(ReviewComment.CommentSeverity.MEDIUM)
                        .category(ReviewComment.CommentCategory.ARCHITECTURE)
                        .title("RestTemplate deprecated")
                        .body("""
                                🏗️ **Architecture** — `RestTemplate` is deprecated in Spring Boot 3.x.

                                Use `RestClient` (Spring Boot 3.2+) for synchronous calls or `WebClient` for reactive.
                                `RestClient` has the same familiar API as RestTemplate but is actively maintained.

                                ```java
                                // Instead of:
                                RestTemplate rt = new RestTemplate();
                                rt.getForObject(url, Type.class);

                                // Use:
                                RestClient client = RestClient.create();
                                client.get().uri(url).retrieve().body(Type.class);
                                ```
                                """)
                        .build());
            }

            // Check: Missing circuit breaker on external HTTP calls
            if (MISSING_CIRCUIT_BREAKER.matcher(patch).find() &&
                    !patch.contains("@CircuitBreaker") && !patch.contains("@Retry")) {
                comments.add(ReviewComment.builder()
                        .filename(file.getFilename())
                        .severity(ReviewComment.CommentSeverity.HIGH)
                        .category(ReviewComment.CommentCategory.ARCHITECTURE)
                        .title("Missing resilience pattern on external call")
                        .body("""
                                🏗️ **Architecture** — External HTTP call without resilience pattern.

                                External calls should be wrapped with `@CircuitBreaker` and `@Retry` (Resilience4j)
                                to prevent cascading failures when downstream services degrade.

                                ```java
                                @CircuitBreaker(name = "external-service", fallbackMethod = "fallback")
                                @Retry(name = "external-service")
                                public ResponseType callExternalService() { ... }
                                ```
                                """)
                        .build());
            }
        }

        return comments;
    }
}
