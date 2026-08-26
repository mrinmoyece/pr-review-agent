package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewPass;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.model.ReviewRoundResult;
import com.agentforge.prreview.security.GitHubCredentialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitHubCommentToolTest {

    private static final String AGENT_URL = "https://github.com/mrinmoyece/pr-review-agent";

    private GitHubCommentTool tool() {
        return new GitHubCommentTool(mock(RestClient.class), mock(GitHubCredentialProvider.class),
                AGENT_URL);
    }

    @Test
    void coverageTableIncludesStatusIconAndEscapedDetail() {
        ReviewRoundResult round = ReviewRoundResult.builder()
                .pass(ReviewPass.SECURITY)
                .status(ReviewRoundResult.RoundStatus.TRUNCATED)
                .model("model")
                .chunksReviewed(3)
                .comments(List.of())
                .detail("cap | reached\npartial")
                .build();
        ReviewResult result = ReviewResult.builder()
                .verdict(ReviewResult.ReviewVerdict.COMMENT)
                .overallScore(100)
                .comments(List.of())
                .reviewRounds(List.of(round))
                .reviewComplete(false)
                .build();

        String body = tool().buildReviewBody(result);

        assertThat(body).contains("Multi-Agent Review Coverage");
        assertThat(body).contains("SECURITY");
        assertThat(body).contains("⚠️");
        assertThat(body).contains("`model`");
        assertThat(body).contains("3");
        assertThat(body).contains("cap \\| reached<br>partial");
    }

    @Test
    void provenanceLinkUsesAgentUrlNotReviewedRepo() {
        ReviewResult result = ReviewResult.builder()
                .verdict(ReviewResult.ReviewVerdict.APPROVE)
                .overallScore(95)
                .comments(List.of())
                .repositoryFullName("victim/repo")
                .reviewComplete(true)
                .build();

        String body = tool().buildReviewBody(result);

        assertThat(body).contains(AGENT_URL);
        assertThat(body).doesNotContain("](victim/repo)");
    }

    @Test
    void untrustedContentIsEscapedInReport() {
        ReviewComment comment = ReviewComment.builder()
                .title("Injection </details> attempt")
                .body("Body with > [!CAUTION] spoofed callout")
                .severity(ReviewComment.CommentSeverity.HIGH)
                .category(ReviewComment.CommentCategory.SECURITY)
                .build();
        ReviewResult result = ReviewResult.builder()
                .verdict(ReviewResult.ReviewVerdict.REQUEST_CHANGES)
                .overallScore(30)
                .comments(List.of(comment))
                .reviewComplete(true)
                .build();

        String body = tool().buildReviewBody(result);

        assertThat(body).doesNotContain("</details>attempt");
        assertThat(body).contains("&lt;/details&gt;");
        assertThat(body).contains("\\[!");
    }

    @Test
    void safeTextEscapesHtmlAndAlertSyntax() {
        assertThat(GitHubCommentTool.safeText("</details>")).isEqualTo("&lt;/details&gt;");
        assertThat(GitHubCommentTool.safeText("> [!CAUTION]")).isEqualTo("&gt; \\[!CAUTION]");
        assertThat(GitHubCommentTool.safeText(null)).isEmpty();
    }

    @Test
    void safeTitleEscapesHtmlEntitiesForSummaryContext() {
        assertThat(GitHubCommentTool.safeTitle("<script>")).isEqualTo("&lt;script&gt;");
        assertThat(GitHubCommentTool.safeTitle("a & b")).isEqualTo("a &amp; b");
        assertThat(GitHubCommentTool.safeTitle(null)).isEmpty();
    }
}
