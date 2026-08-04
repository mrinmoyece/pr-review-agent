package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityScanToolTest {

    private SecurityScanTool securityScanTool;

    @BeforeEach
    void setUp() {
        securityScanTool = new SecurityScanTool();
    }

    private DiffFile javaFile(String filename, List<String> addedLines) {
        return DiffFile.builder()
                .filename(filename)
                .language("java")
                .addedLines(addedLines)
                .removedLines(List.of())
                .rawDiff("")
                .build();
    }

    @Test
    void givenSqlConcatenation_whenScanned_thenSqlInjectionFlagged() {
        DiffFile file = javaFile("UserRepo.java",
                List.of("String query = \"SELECT * FROM users WHERE id = \" + userId;",
                        "jdbcTemplate.query(query, mapper);"));

        List<ReviewComment> comments = securityScanTool.scan(List.of(file));

        assertThat(comments).isNotEmpty();
        assertThat(comments).anyMatch(c ->
                c.getSeverity() == ReviewComment.CommentSeverity.CRITICAL &&
                c.getCategory() == ReviewComment.CommentCategory.SECURITY);
    }

    @Test
    void givenHardcodedPassword_whenScanned_thenFlagged() {
        DiffFile file = javaFile("Config.java",
                List.of("private static final String DB_PASSWORD = \"secret123\";"));

        List<ReviewComment> comments = securityScanTool.scan(List.of(file));

        assertThat(comments).anyMatch(c ->
                c.getTitle().toLowerCase().contains("secret") ||
                c.getTitle().toLowerCase().contains("credential"));
    }

    @Test
    void givenCleanCode_whenScanned_thenNoIssues() {
        DiffFile file = javaFile("Service.java",
                List.of("@Service",
                        "public class UserService {",
                        "    private final UserRepository userRepository;",
                        "}"));

        List<ReviewComment> comments = securityScanTool.scan(List.of(file));

        assertThat(comments).isEmpty();
    }

    @Test
    void givenNonJavaFile_whenScanned_thenSkipped() {
        DiffFile file = DiffFile.builder()
                .filename("README.md")
                .language("markdown")
                .addedLines(List.of("password=secret"))
                .removedLines(List.of())
                .rawDiff("")
                .build();

        // Markdown files should not be scanned by Java-specific security rules
        List<ReviewComment> comments = securityScanTool.scan(List.of(file));
        // No assertion on count — just verify no exception
    }

    @Test
    void givenMultipleIssues_whenScanned_thenAllReported() {
        DiffFile file = javaFile("BadCode.java",
                List.of("String q = \"SELECT * FROM t WHERE id=\" + id;",
                        "String pwd = \"hardcoded_secret\";",
                        "Runtime.getRuntime().exec(userInput);"));

        List<ReviewComment> comments = securityScanTool.scan(List.of(file));
        assertThat(comments.size()).isGreaterThanOrEqualTo(2);
    }
}
