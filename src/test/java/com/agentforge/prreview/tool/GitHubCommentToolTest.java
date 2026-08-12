package com.agentforge.prreview.tool;

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

    @Test
    void coverageTableIncludesChunksAndEscapedDetail() {
        GitHubCommentTool tool = new GitHubCommentTool(
                mock(RestClient.class), mock(GitHubCredentialProvider.class));
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

        String body = tool.buildReviewBody(result);

        assertThat(body).contains("| Pass | Status | Model | Chunks | Findings | Detail |");
        assertThat(body).contains("| SECURITY | TRUNCATED | `model` | 3 | 0"
                + " | cap \\| reached<br>partial |");
    }
}
