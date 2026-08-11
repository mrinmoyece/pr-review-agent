package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.ReviewComment;
import com.azure.ai.openai.OpenAIClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitHubAutoFixToolTest {

    private GitHubAutoFixTool tool;

    @BeforeEach
    void setUp() {
        tool = new GitHubAutoFixTool(
                mock(RestClient.class),
                mock(OpenAIClient.class),
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(tool, "autoFixEnabled", true);
    }

    private ReviewComment comment(ReviewComment.CommentSeverity severity, boolean autoFixable) {
        return comment(severity, autoFixable, "src/main/java/Foo.java");
    }

    private ReviewComment comment(ReviewComment.CommentSeverity severity, boolean autoFixable, String filename) {
        return ReviewComment.builder()
                .severity(severity)
                .autoFixable(autoFixable)
                .filename(filename)
                .title("Test issue")
                .body("Details")
                .category(ReviewComment.CommentCategory.STYLE)
                .build();
    }

    @Test
    void givenCriticalSeverity_whenApplyFixes_thenSkipped() {
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.CRITICAL, true)
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

        // CRITICAL is ineligible regardless of autoFixable flag
        assertThat(result).isEmpty();
    }

    @Test
    void givenHighSeverity_whenApplyFixes_thenSkipped() {
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.HIGH, true)
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

        // HIGH is ineligible regardless of autoFixable flag
        assertThat(result).isEmpty();
    }

    @Test
    void givenNoEligibleComments_whenApplyFixes_thenReturnsEmptyList() {
        // autoFixable=false, so none qualify even at LOW severity
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.LOW, false),
                comment(ReviewComment.CommentSeverity.MEDIUM, false)
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

        assertThat(result).isEmpty();
    }

    @Test
    void givenAutoFixDisabled_whenApplyFixes_thenReturnsEmptyList() {
        ReflectionTestUtils.setField(tool, "autoFixEnabled", false);

        List<AutoFix> result = tool.applyFixes(
                "org/repo", "feature-branch",
                List.of(comment(ReviewComment.CommentSeverity.LOW, true)));

        assertThat(result).isEmpty();
    }

    @Test
    void givenSensitiveWorkflowPath_whenApplyFixes_thenReturnsEmptyList() {
        List<AutoFix> result = tool.applyFixes(
                "org/repo", "feature-branch",
                List.of(comment(ReviewComment.CommentSeverity.LOW, true,
                        ".github/workflows/ci.yml")));

        assertThat(result).isEmpty();
    }

    @Test
    void givenMultipleLowCommentsOnSameFile_whenApplyFixes_thenGroupedIntoSingleEntry() {
        // Both LOW comments on the same file should produce one AutoFix entry
        // (either committed or skipped — here they'll be skipped since GitHub REST is mocked)
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.LOW, true, "src/Foo.java"),
                comment(ReviewComment.CommentSeverity.LOW, true, "src/Foo.java")
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

        // One entry per file, not one entry per comment
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFilename()).isEqualTo("src/Foo.java");
    }

    @Test
    void givenMultipleLowCommentsOnDifferentFiles_whenApplyFixes_thenOneEntryPerFile() {
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.LOW, true, "src/Foo.java"),
                comment(ReviewComment.CommentSeverity.LOW, true, "src/Bar.java")
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

        // Two files -> two entries (both will be skipped due to mocked REST client)
        assertThat(result).hasSize(2);
    }

    @Test
    void fixFileFallback_returnsSkippedAutoFix() {
        // The static factory is pure logic — no mocks needed
        AutoFix skipped = AutoFix.skipped("src/Foo.java", "GitHub API unavailable: circuit open");

        assertThat(skipped.isApplied()).isFalse();
        assertThat(skipped.getFilename()).isEqualTo("src/Foo.java");
        assertThat(skipped.getSkipReason()).contains("circuit open");
    }
}
