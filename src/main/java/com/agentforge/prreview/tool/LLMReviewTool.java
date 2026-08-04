package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.ReviewComment;
import com.agentforge.prreview.model.ReviewResult;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * LLM-powered holistic code review.
 * Runs AFTER static analysis tools — so the LLM has context about what
 * rule-based checks already found, and focuses on nuanced issues those miss:
 * - Business logic correctness
 * - Subtle race conditions
 * - Missing error handling paths
 * - Code clarity and maintainability
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LLMReviewTool {

    private final OpenAIClient openAIClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.chat-deployment:gpt-4o}")
    private String deployment;

    @Retry(name = "llm-retry")
    public List<ReviewComment> review(String diff, List<ReviewComment> existingComments, String teamPatterns) {
        String systemPrompt = loadPrompt("prompts/pr-review.md");
        String userPrompt = """
                TEAM CODING STANDARDS (learned from this repo's past reviews):
                %s

                The following static analysis tools have already run and found these issues:
                %s

                Now review this PR diff for issues the static tools may have MISSED.
                Apply the team coding standards above when judging code quality.
                DO NOT repeat issues already flagged above.
                Return only NEW issues as a JSON array of ReviewComment objects.

                DIFF:
                ```diff
                %s
                ```
                """.formatted(
                    teamPatterns.isBlank() ? "No history available -- use general best practices." : teamPatterns,
                    formatExistingComments(existingComments),
                    diff);

        try {
            var options = new ChatCompletionsOptions(List.of(
                    new ChatRequestSystemMessage(systemPrompt),
                    new ChatRequestUserMessage(userPrompt)
            ));
            options.setMaxTokens(3000);
            options.setTemperature(0.2);

            ChatCompletions completions = openAIClient.getChatCompletions(deployment, options);
            String response = completions.getChoices().get(0).getMessage().getContent();
            String json = extractJson(response);
            return objectMapper.readValue(json, new TypeReference<List<ReviewComment>>() {});
        } catch (Exception e) {
            log.error("LLM review failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public String generateSummary(List<ReviewComment> comments,
                                  ReviewResult.ReviewVerdict verdict, int score) {
        String prompt = """
                Generate a concise PR review summary (3-4 sentences, markdown).
                Verdict: %s | Score: %d/100 | Issues: %d total
                Key issues: %s
                """.formatted(verdict, score, comments.size(), formatExistingComments(comments));

        var options = new ChatCompletionsOptions(List.of(new ChatRequestUserMessage(prompt)));
        options.setMaxTokens(300);
        return openAIClient.getChatCompletions(deployment, options)
                .getChoices().get(0).getMessage().getContent();
    }

    private String formatExistingComments(List<ReviewComment> comments) {
        if (comments.isEmpty()) return "None";
        return comments.stream()
                .map(c -> "[%s] %s: %s".formatted(c.getSeverity(), c.getCategory(), c.getBody()))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private String extractJson(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']') + 1;
        return (start >= 0 && end > start) ? response.substring(start, end) : "[]";
    }

    private String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt: " + path, e);
        }
    }
}
