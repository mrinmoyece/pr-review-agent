package com.agentforge.prreview.agent;

import com.agentforge.prreview.model.AdversarialVerificationResult;
import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewPass;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.model.ReviewRoundResult;
import com.agentforge.prreview.tool.ArchitectureCheckTool;
import com.agentforge.prreview.tool.GitHubAutoFixTool;
import com.agentforge.prreview.tool.GitHubCommentTool;
import com.agentforge.prreview.tool.GitHubDiffTool;
import com.agentforge.prreview.tool.JiraTicketTool;
import com.agentforge.prreview.tool.LLMReviewTool;
import com.agentforge.prreview.tool.PerformanceAnalysisTool;
import com.agentforge.prreview.tool.ReviewHistoryTool;
import com.agentforge.prreview.tool.SecurityScanTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PRReviewAgentTest {

    private static final String REPOSITORY = "org/repo";

    private LLMReviewTool llmReviewTool;
    private SecurityScanTool securityTool;
    private PRReviewAgent agent;

    @BeforeEach
    void setUp() {
        GitHubDiffTool diffTool = mock(GitHubDiffTool.class);
        securityTool = mock(SecurityScanTool.class);
        ArchitectureCheckTool architectureTool = mock(ArchitectureCheckTool.class);
        PerformanceAnalysisTool performanceTool = mock(PerformanceAnalysisTool.class);
        llmReviewTool = mock(LLMReviewTool.class);
        GitHubCommentTool commentTool = mock(GitHubCommentTool.class);
        JiraTicketTool jiraTool = mock(JiraTicketTool.class);
        ReviewHistoryTool historyTool = mock(ReviewHistoryTool.class);
        GitHubAutoFixTool autoFixTool = mock(GitHubAutoFixTool.class);

        agent = new PRReviewAgent(diffTool, securityTool, architectureTool,
                performanceTool, llmReviewTool, commentTool, jiraTool,
                historyTool, autoFixTool, Runnable::run);
        ReflectionTestUtils.setField(agent, "repositoryAllowlist", REPOSITORY);

        DiffFile file = DiffFile.builder()
                .filename("src/main/java/Foo.java")
                .rawDiff("@@ -1 +1 @@\n+changed\n")
                .build();
        when(diffTool.fetchDiff(REPOSITORY, 42)).thenReturn("diff");
        when(diffTool.parseDiff("diff")).thenReturn(List.of(file));
        when(securityTool.scan(any())).thenReturn(List.of());
        when(architectureTool.check(any())).thenReturn(List.of());
        when(performanceTool.analyse(any())).thenReturn(List.of());
        when(jiraTool.checkAlignment(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(historyTool.loadTeamPatterns(REPOSITORY)).thenReturn("");
        when(llmReviewTool.generateSummary(any(), any(), anyInt())).thenReturn("summary");
    }

    @Test
    void truncatedSpecialistRoundCannotApprove() {
        when(llmReviewTool.review(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> round(invocation.getArgument(0),
                        ReviewRoundResult.RoundStatus.TRUNCATED, List.of()));
        when(llmReviewTool.verify(any(), any(), anyString()))
                .thenReturn(verification(ReviewRoundResult.RoundStatus.COMPLETE, List.of()));

        ReviewResult result = review();

        assertThat(result.isReviewComplete()).isFalse();
        assertThat(result.getVerdict()).isEqualTo(ReviewResult.ReviewVerdict.COMMENT);
    }

    @Test
    void rejectedSpecialistFindingIsExcludedFromVerdict() {
        ReviewComment rejected = blockingComment();
        when(llmReviewTool.review(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> round(invocation.getArgument(0),
                        ReviewRoundResult.RoundStatus.COMPLETE, List.of(rejected)));
        when(llmReviewTool.verify(any(), any(), anyString()))
                .thenReturn(verification(ReviewRoundResult.RoundStatus.COMPLETE, List.of()));

        ReviewResult result = review();

        assertThat(result.getComments()).isEmpty();
        assertThat(result.getVerdict()).isEqualTo(ReviewResult.ReviewVerdict.APPROVE);
    }

    @Test
    void verificationFailureRetainsCandidateAndBlocksMerge() {
        ReviewComment candidate = blockingComment();
        when(llmReviewTool.review(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> round(invocation.getArgument(0),
                        ReviewRoundResult.RoundStatus.COMPLETE, List.of(candidate)));
        when(llmReviewTool.verify(any(), any(), anyString()))
                .thenReturn(verification(ReviewRoundResult.RoundStatus.FAILED,
                        List.of(candidate)));

        ReviewResult result = review();

        assertThat(result.isReviewComplete()).isFalse();
        assertThat(result.getComments()).containsExactly(candidate);
        assertThat(result.getVerdict())
                .isEqualTo(ReviewResult.ReviewVerdict.REQUEST_CHANGES);
    }

    @Test
    void aggregateDeduplicationRetainsHighestSeverity() {
        ReviewComment low = blockingComment();
        low.setSeverity(ReviewComment.CommentSeverity.LOW);
        ReviewComment high = blockingComment();
        when(securityTool.scan(any())).thenReturn(List.of(low));
        when(llmReviewTool.review(any(), any(), any(), anyString()))
                .thenAnswer(invocation -> round(invocation.getArgument(0),
                        ReviewRoundResult.RoundStatus.COMPLETE, List.of(high)));
        when(llmReviewTool.verify(any(), any(), anyString()))
                .thenReturn(verification(ReviewRoundResult.RoundStatus.COMPLETE,
                        List.of(high)));

        ReviewResult result = review();

        assertThat(result.getComments()).singleElement()
                .extracting(ReviewComment::getSeverity)
                .isEqualTo(ReviewComment.CommentSeverity.HIGH);
        assertThat(result.getVerdict())
                .isEqualTo(ReviewResult.ReviewVerdict.REQUEST_CHANGES);
    }

    private ReviewResult review() {
        return agent.review(REPOSITORY, 42, "feature", "title", "body").join();
    }

    private ReviewRoundResult round(ReviewPass pass,
                                    ReviewRoundResult.RoundStatus status,
                                    List<ReviewComment> comments) {
        return ReviewRoundResult.builder()
                .pass(pass)
                .status(status)
                .model("model")
                .comments(comments)
                .build();
    }

    private AdversarialVerificationResult verification(
            ReviewRoundResult.RoundStatus status,
            List<ReviewComment> confirmed) {
        return AdversarialVerificationResult.builder()
                .round(round(ReviewPass.ADVERSARIAL_VERIFICATION, status, List.of()))
                .confirmedComments(confirmed)
                .build();
    }

    private ReviewComment blockingComment() {
        return ReviewComment.builder()
                .filename("src/main/java/Foo.java")
                .lineNumber(1)
                .category(ReviewComment.CommentCategory.CORRECTNESS)
                .severity(ReviewComment.CommentSeverity.HIGH)
                .title("Blocking")
                .body("Evidence")
                .build();
    }
}
