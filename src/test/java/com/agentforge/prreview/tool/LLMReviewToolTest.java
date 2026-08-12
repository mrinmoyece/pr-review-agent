package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AdversarialVerificationResult;
import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewPass;
import com.agentforge.prreview.model.ReviewRoundResult;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class LLMReviewToolTest {

    private LLMReviewTool tool;
    private Environment environment;
    private OpenAIClient openAIClient;

    @BeforeEach
    void setUp() {
        environment = mock(Environment.class);
        openAIClient = mock(OpenAIClient.class);
        when(environment.getProperty(anyString(), anyString())).thenReturn("test-model");
        tool = spy(new LLMReviewTool(
                openAIClient,
                new ObjectMapper(),
                environment,
                RetryRegistry.ofDefaults()));
        ReflectionTestUtils.setField(tool, "defaultDeployment", "test-model");
        ReflectionTestUtils.setField(tool, "chunkCharacters", 500);
        ReflectionTestUtils.setField(tool, "maxDiffCharacters", 10_000);
        ReflectionTestUtils.setField(tool, "maxFindingsPerPass", 25);
        ReflectionTestUtils.setField(tool, "maxChunksPerPass", 20);
        ReflectionTestUtils.setField(tool, "verificationCandidatesPerRequest", 20);
    }

    @Test
    void malformedResponseFailsRound() {
        doReturn("not json").when(tool).requestCompletion(anyString(), any());

        ReviewRoundResult result = tool.review(
                ReviewPass.SECURITY, List.of(diff("src/main/java/Foo.java")),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.FAILED);
        assertThat(result.getChunksReviewed()).isZero();
    }

    @Test
    void invalidFilesAreDiscardedAndIncrementLineRemainsAnchorable() {
        doReturn("""
                [
                  {"filename":"other/File.java","lineNumber":1,"category":"CORRECTNESS",
                   "severity":"HIGH","title":"outside","body":"outside","autoFixable":false},
                  {"filename":"src/main/java/Foo.java","lineNumber":1,"category":"CORRECTNESS",
                   "severity":"LOW","title":"increment","body":"valid","autoFixable":true,
                   "suggestedFix":"untrusted"}
                ]
                """).when(tool).requestCompletion(anyString(), any());

        ReviewRoundResult result = tool.review(
                ReviewPass.CORRECTNESS, List.of(diff("src/main/java/Foo.java")),
                List.of(), "");

        assertThat(result.getComments()).singleElement().satisfies(comment -> {
            assertThat(comment.getLineNumber()).isEqualTo(1);
            assertThat(comment.isAutoFixable()).isFalse();
            assertThat(comment.getSuggestedFix()).isNull();
        });
    }

    @Test
    void finalChunkFindingOverflowMarksRoundTruncated() {
        ReflectionTestUtils.setField(tool, "maxFindingsPerPass", 1);
        doReturn("""
                [
                  {"filename":"src/main/java/Foo.java","lineNumber":1,"category":"CORRECTNESS",
                   "severity":"LOW","title":"one","body":"one","autoFixable":false},
                  {"filename":"src/main/java/Foo.java","lineNumber":1,"category":"STYLE",
                   "severity":"LOW","title":"two","body":"two","autoFixable":false}
                ]
                """).when(tool).requestCompletion(anyString(), any());

        ReviewRoundResult result = tool.review(
                ReviewPass.CORRECTNESS, List.of(diff("src/main/java/Foo.java")),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.TRUNCATED);
        assertThat(result.getComments()).hasSize(1);
        assertThat(result.getDetail()).contains("exceeded");
    }

    @Test
    void lineBoundedChunksRetainFileContext() {
        ReflectionTestUtils.setField(tool, "chunkCharacters", 220);
        doReturn("[]").when(tool).requestCompletion(anyString(), any());
        String rawDiff = """
                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                --- a/src/main/java/Foo.java
                +++ b/src/main/java/Foo.java
                @@ -1,2 +1,8 @@
                +first();
                +second();
                +third();
                +fourth();
                +fifth();
                +sixth();
                +seventh();
                +eighth();
                """;

        ReviewRoundResult result = tool.review(
                ReviewPass.TESTING,
                List.of(DiffFile.builder()
                        .filename("src/main/java/Foo.java")
                        .rawDiff(rawDiff)
                        .build()),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.COMPLETE);
        assertThat(result.getChunksReviewed()).isGreaterThan(1);
        assertThat(tool.chunkDiff(List.of(DiffFile.builder()
                .filename("src/main/java/Foo.java")
                .rawDiff(rawDiff)
                .build())).chunks())
                .anyMatch(chunk -> chunk.matches("(?s).*@@ -1 \\+[2-9][0-9]* @@.*"));
    }

    @Test
    void chunkLimitMarksCoverageTruncated() {
        ReflectionTestUtils.setField(tool, "chunkCharacters", 220);
        ReflectionTestUtils.setField(tool, "maxChunksPerPass", 1);
        doReturn("[]").when(tool).requestCompletion(anyString(), any());

        ReviewRoundResult result = tool.review(
                ReviewPass.TESTING,
                List.of(DiffFile.builder()
                        .filename("src/main/java/Foo.java")
                        .rawDiff("""
                                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                                --- a/src/main/java/Foo.java
                                +++ b/src/main/java/Foo.java
                                @@ -1,2 +1,8 @@
                                +first();
                                +second();
                                +third();
                                +fourth();
                                +fifth();
                                +sixth();
                                +seventh();
                                +eighth();
                                """)
                        .build()),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.TRUNCATED);
        assertThat(result.getChunksReviewed()).isEqualTo(1);
    }

    @Test
    void totalDiffLimitFlushesAcceptedPendingEvidence() {
        ReflectionTestUtils.setField(tool, "maxDiffCharacters", 30);
        doReturn("[]").when(tool).requestCompletion(anyString(), any());

        ReviewRoundResult result = tool.review(
                ReviewPass.TESTING,
                List.of(DiffFile.builder()
                        .filename("src/main/java/Foo.java")
                        .rawDiff("""
                                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                                --- a/src/main/java/Foo.java
                                +++ b/src/main/java/Foo.java
                                @@ -1 +1,3 @@
                                +first();
                                +second();
                                +third();
                                """)
                        .build()),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.TRUNCATED);
        assertThat(result.getChunksReviewed()).isEqualTo(1);
    }

    @Test
    void failureAfterFirstChunkReportsPartialCoverage() {
        ReflectionTestUtils.setField(tool, "chunkCharacters", 220);
        doReturn("""
                [{"filename":"src/main/java/Foo.java","lineNumber":1,
                  "category":"CORRECTNESS","severity":"HIGH",
                  "title":"partial","body":"preserved","autoFixable":false}]
                """).doThrow(new IllegalStateException("provider failed"))
                .when(tool).requestCompletion(anyString(), any());
        String rawDiff = """
                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                --- a/src/main/java/Foo.java
                +++ b/src/main/java/Foo.java
                @@ -1,2 +1,8 @@
                +first();
                +second();
                +third();
                +fourth();
                +fifth();
                +sixth();
                +seventh();
                +eighth();
                """;

        ReviewRoundResult result = tool.review(
                ReviewPass.OPERATIONS,
                List.of(DiffFile.builder()
                        .filename("src/main/java/Foo.java")
                        .rawDiff(rawDiff)
                        .build()),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.FAILED);
        assertThat(result.getChunksReviewed()).isEqualTo(1);
        assertThat(result.getComments()).singleElement()
                .extracting(ReviewComment::getTitle).isEqualTo("partial");
    }

    @Test
    void verificationFiltersRejectedFindings() throws Exception {
        ReviewComment confirmed = comment("confirmed");
        ReviewComment rejected = comment("rejected");
        String confirmedId = findingId(confirmed);
        String rejectedId = findingId(rejected);
        doReturn("""
                {"decisions":[
                  {"findingId":"%s","verdict":"CONFIRMED","reason":"evidence"},
                  {"findingId":"%s","verdict":"REJECTED","reason":"unsupported"}
                ],"newFindings":[]}
                """.formatted(confirmedId, rejectedId))
                .when(tool).requestCompletion(anyString(), any());

        AdversarialVerificationResult result = tool.verify(
                List.of(diff("src/main/java/Foo.java")),
                List.of(confirmed, rejected), "");

        assertThat(result.getRound().getStatus())
                .isEqualTo(ReviewRoundResult.RoundStatus.COMPLETE);
        assertThat(result.getConfirmedComments()).containsExactly(confirmed);
    }

    @Test
    void verificationBatchesCandidateDecisions() throws Exception {
        ReflectionTestUtils.setField(tool, "verificationCandidatesPerRequest", 1);
        ReviewComment first = comment("first");
        ReviewComment second = comment("second");
        doReturn("""
                {"decisions":[{"findingId":"%s","verdict":"CONFIRMED",
                "reason":"evidence"}],"newFindings":[]}
                """.formatted(findingId(first)))
                .doReturn("""
                {"decisions":[{"findingId":"%s","verdict":"REJECTED",
                "reason":"unsupported"}],"newFindings":[]}
                """.formatted(findingId(second)))
                .when(tool).requestCompletion(anyString(), any());

        AdversarialVerificationResult result = tool.verify(
                List.of(diff("src/main/java/Foo.java")),
                List.of(first, second), "");

        assertThat(result.getRound().getStatus())
                .isEqualTo(ReviewRoundResult.RoundStatus.COMPLETE);
        assertThat(result.getRound().getChunksReviewed()).isEqualTo(2);
        assertThat(result.getConfirmedComments()).containsExactly(first);
    }

    @Test
    void missingVerificationDecisionFailsClosed() throws Exception {
        ReviewComment first = comment("first");
        ReviewComment second = comment("second");
        doReturn("""
                {"decisions":[
                  {"findingId":"%s","verdict":"CONFIRMED","reason":"evidence"}
                ],"newFindings":[]}
                """.formatted(findingId(first)))
                .when(tool).requestCompletion(anyString(), any());

        AdversarialVerificationResult result = tool.verify(
                List.of(diff("src/main/java/Foo.java")),
                List.of(first, second), "");

        assertThat(result.getRound().getStatus())
                .isEqualTo(ReviewRoundResult.RoundStatus.FAILED);
        assertThat(result.getConfirmedComments()).containsExactly(first, second);
    }

    @Test
    void emptyCandidateSetStillRunsAdversarialDiscovery() {
        doReturn("""
                {"decisions":[],"newFindings":[]}
                """).when(tool).requestCompletion(anyString(), any());

        AdversarialVerificationResult result = tool.verify(
                List.of(diff("src/main/java/Foo.java")), List.of(), "");

        assertThat(result.getRound().getStatus())
                .isEqualTo(ReviewRoundResult.RoundStatus.COMPLETE);
        assertThat(result.getRound().getChunksReviewed()).isEqualTo(1);
    }

    @Test
    void hunklessDiffMarksCoverageTruncated() {
        ReviewRoundResult result = tool.review(
                ReviewPass.SECURITY,
                List.of(DiffFile.builder()
                        .filename("image.png")
                        .rawDiff("""
                                diff --git a/image.png b/image.png
                                Binary files a/image.png and b/image.png differ
                                """)
                        .build()),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.TRUNCATED);
        assertThat(result.getChunksReviewed()).isZero();
    }

    @Test
    void hunklessFileDoesNotPreventBestEffortTextReview() {
        doReturn("[]").when(tool).requestCompletion(anyString(), any());

        ReviewRoundResult result = tool.review(
                ReviewPass.SECURITY,
                List.of(
                        DiffFile.builder()
                                .filename("image.png")
                                .rawDiff("Binary files a/image.png and b/image.png differ")
                                .build(),
                        diff("src/main/java/Foo.java")),
                List.of(), "");

        assertThat(result.getStatus()).isEqualTo(ReviewRoundResult.RoundStatus.TRUNCATED);
        assertThat(result.getChunksReviewed()).isEqualTo(1);
    }

    @Test
    void tokenLimitedCompletionIsRejected() {
        ChatCompletions completions = mock(ChatCompletions.class);
        ChatChoice choice = mock(ChatChoice.class);
        ChatResponseMessage message = mock(ChatResponseMessage.class);
        when(openAIClient.getChatCompletions(anyString(), any())).thenReturn(completions);
        when(completions.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);
        when(choice.getFinishReason()).thenReturn(CompletionsFinishReason.TOKEN_LIMIT_REACHED);
        when(message.getContent()).thenReturn("[]");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tool.requestCompletion("test-model", mock(
                                com.azure.ai.openai.models.ChatCompletionsOptions.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncatedVerificationEvidenceFailsClosedAndRetainsCandidates() {
        ReflectionTestUtils.setField(tool, "maxDiffCharacters", 1);
        ReviewComment candidate = comment("candidate");

        AdversarialVerificationResult result = tool.verify(
                List.of(diff("src/main/java/Foo.java")), List.of(candidate), "");

        assertThat(result.getRound().getStatus())
                .isEqualTo(ReviewRoundResult.RoundStatus.FAILED);
        assertThat(result.getConfirmedComments()).containsExactly(candidate);
    }

    private DiffFile diff(String filename) {
        return DiffFile.builder()
                .filename(filename)
                .rawDiff("""
                        diff --git a/%s b/%s
                        --- a/%s
                        +++ b/%s
                        @@ -1 +1 @@
                        +++counter;
                        """.formatted(filename, filename, filename, filename))
                .build();
    }

    private ReviewComment comment(String title) {
        return ReviewComment.builder()
                .filename("src/main/java/Foo.java")
                .lineNumber(1)
                .category(ReviewComment.CommentCategory.CORRECTNESS)
                .severity(ReviewComment.CommentSeverity.HIGH)
                .title(title)
                .body("body")
                .build();
    }

    private String findingId(ReviewComment comment) throws Exception {
        String value = "%s|%s|%s|%s|%s".formatted(
                comment.getFilename(), comment.getLineNumber(), comment.getCategory(),
                comment.getSeverity(), comment.getTitle());
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 12);
    }
}
