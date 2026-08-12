package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.ReviewComment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubAutoFixToolTest {

    private GitHubAutoFixTool tool;

    @BeforeEach
    void setUp() {
        tool = new GitHubAutoFixTool();
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
    void trustedPolicyIgnoresModelFlagAndAuthorizesOnlyAnchoredLowStyleFindings() {
        ReviewComment eligible = comment(ReviewComment.CommentSeverity.LOW, false);
        eligible.setLineNumber(10);
        ReviewComment wrongCategory = comment(ReviewComment.CommentSeverity.LOW, true);
        wrongCategory.setCategory(ReviewComment.CommentCategory.CORRECTNESS);
        wrongCategory.setLineNumber(10);

        tool.authorizeEligible(List.of(eligible, wrongCategory));

        assertThat(eligible.isAutoFixable()).isTrue();
        assertThat(wrongCategory.isAutoFixable()).isFalse();
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
    void givenDeploymentOrNestedDockerPath_whenApplyFixes_thenReturnsEmptyList() {
        List<AutoFix> result = tool.applyFixes(
                "org/repo", "feature-branch",
                List.of(
                        comment(ReviewComment.CommentSeverity.LOW, true,
                                "docker-compose.yml"),
                        comment(ReviewComment.CommentSeverity.LOW, true,
                                "services/api/Dockerfile"),
                        comment(ReviewComment.CommentSeverity.LOW, true,
                                "charts/app/templates/deployment.yaml")));

        assertThat(result).isEmpty();
    }

    @Test
    void givenNonJavaSourcePath_whenApplyFixes_thenReturnsEmptyList() {
        List<AutoFix> result = tool.applyFixes(
                "org/repo", "feature-branch",
                List.of(comment(ReviewComment.CommentSeverity.LOW, true,
                        "src/main/resources/application.yml")));

        assertThat(result).isEmpty();
    }

    @Test
    void givenCaseMismatchedSourceRoot_whenApplyFixes_thenReturnsEmptyList() {
        List<AutoFix> result = tool.applyFixes(
                "org/repo", "feature-branch",
                List.of(comment(ReviewComment.CommentSeverity.LOW, true,
                        "SRC/MAIN/JAVA/Foo.java")));

        assertThat(result).isEmpty();
    }

    @Test
    void givenMultipleLowCommentsOnSameFile_whenApplyFixes_thenGroupedIntoSingleEntry() {
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.LOW, true, "src/main/java/Foo.java"),
                comment(ReviewComment.CommentSeverity.LOW, true, "src/main/java/Foo.java")
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFilename()).isEqualTo("src/main/java/Foo.java");
        assertThat(result.get(0).getSkipReason()).contains("human approval");
    }

    @Test
    void givenMultipleLowCommentsOnDifferentFiles_whenApplyFixes_thenOneEntryPerFile() {
        List<ReviewComment> comments = List.of(
                comment(ReviewComment.CommentSeverity.LOW, true, "src/main/java/Foo.java"),
                comment(ReviewComment.CommentSeverity.LOW, true, "src/main/java/Bar.java")
        );

        List<AutoFix> result = tool.applyFixes("org/repo", "feature-branch", comments);

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
