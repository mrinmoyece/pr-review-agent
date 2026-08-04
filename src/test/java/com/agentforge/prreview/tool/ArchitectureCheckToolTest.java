package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureCheckToolTest {

    private ArchitectureCheckTool architectureCheckTool;

    @BeforeEach
    void setUp() {
        architectureCheckTool = new ArchitectureCheckTool();
    }

    private DiffFile javaFileWithDiff(String filename, String rawDiff) {
        return DiffFile.builder()
                .filename(filename)
                .language("java")
                .addedLines(List.of())
                .removedLines(List.of())
                .rawDiff(rawDiff)
                .build();
    }

    @Test
    void givenRestTemplateUsage_whenChecked_thenDeprecationFlagged() {
        DiffFile file = javaFileWithDiff("UserClient.java",
                "+    RestTemplate restTemplate = new RestTemplate();\n"
                + "+    restTemplate.getForObject(url, User.class);");

        List<ReviewComment> comments = architectureCheckTool.check(List.of(file));

        assertThat(comments).anyMatch(c ->
                c.getSeverity() == ReviewComment.CommentSeverity.MEDIUM &&
                c.getCategory() == ReviewComment.CommentCategory.ARCHITECTURE &&
                c.getTitle().contains("RestTemplate"));
    }

    @Test
    void givenExternalCallWithoutResilience_whenChecked_thenHighSeverityFlagged() {
        DiffFile file = javaFileWithDiff("PaymentClient.java",
                "+    var response = restClient.get()\n"
                + "+        .uri(\"/payments\")\n"
                + "+        .retrieve();");

        List<ReviewComment> comments = architectureCheckTool.check(List.of(file));

        assertThat(comments).anyMatch(c ->
                c.getSeverity() == ReviewComment.CommentSeverity.HIGH &&
                c.getCategory() == ReviewComment.CommentCategory.ARCHITECTURE);
    }

    @Test
    void givenExternalCallWithCircuitBreaker_whenChecked_thenNotFlagged() {
        DiffFile file = javaFileWithDiff("PaymentClient.java",
                "+    @CircuitBreaker(name = \"payments\", fallbackMethod = \"fallback\")\n"
                + "+    public Payment fetch() {\n"
                + "+        return restClient.get().uri(\"/payments\").retrieve().body(Payment.class);\n"
                + "+    }");

        List<ReviewComment> comments = architectureCheckTool.check(List.of(file));

        assertThat(comments).noneMatch(c ->
                c.getTitle().contains("resilience")
                        || c.getTitle().contains("Missing resilience pattern on external call"));
    }

    @Test
    void givenNonJavaFile_whenChecked_thenSkipped() {
        DiffFile file = DiffFile.builder()
                .filename("deploy.yaml")
                .language("yaml")
                .addedLines(List.of())
                .removedLines(List.of())
                .rawDiff("+    RestTemplate restTemplate = new RestTemplate();")
                .build();

        List<ReviewComment> comments = architectureCheckTool.check(List.of(file));

        assertThat(comments).isEmpty();
    }

    @Test
    void givenCleanCode_whenChecked_thenNoComments() {
        DiffFile file = javaFileWithDiff("UserService.java",
                "+    @Service\n"
                + "+    public class UserService {\n"
                + "+        private final UserRepository repository;\n"
                + "+    }");

        List<ReviewComment> comments = architectureCheckTool.check(List.of(file));

        assertThat(comments).isEmpty();
    }

    @Test
    void givenMultipleFiles_whenChecked_thenEachEvaluatedIndependently() {
        DiffFile bad = javaFileWithDiff("OldClient.java",
                "+    RestTemplate restTemplate = new RestTemplate();");
        DiffFile clean = javaFileWithDiff("NewClient.java",
                "+    // uses injected RestClient bean with @CircuitBreaker elsewhere");

        List<ReviewComment> comments = architectureCheckTool.check(List.of(bad, clean));

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getFilename()).isEqualTo("OldClient.java");
    }
}
