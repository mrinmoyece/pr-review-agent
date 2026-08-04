package com.agentforge.prreview.tool;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

/**
 * Learns the team's code review patterns from their past PR comments.
 *
 * Fetches the last N closed PRs and their review comments, then asks the LLM
 * to extract recurring patterns (what the team consistently flags). This context
 * is injected into the LLM review prompt so the agent mirrors the team's standards
 * rather than applying generic rules.
 *
 * Result is Caffeine-cached per repository for 1 hour -- expensive to compute,
 * cheap to reuse.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewHistoryTool {

    private final RestClient gitHubRestClient;
    private final OpenAIClient openAIClient;
    private final ObjectMapper objectMapper;

    @Value("${github.token}")
    private String githubToken;

    @Value("${llm.chat-deployment:gpt-4o}")
    private String deployment;

    @Value("${review.history.pr-count:20}")
    private int historyPrCount;

    // Cache: repoFullName -> team coding standards string
    private final Cache<String, String> teamPatternsCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100)
            .build();

    /**
     * Returns a description of this team's recurring code review standards.
     * Returns empty string if history cannot be loaded (fail-open).
     */
    public String loadTeamPatterns(String repoFullName) {
        return teamPatternsCache.get(repoFullName, this::fetchAndExtractPatterns);
    }

    @CircuitBreaker(name = "github", fallbackMethod = "patternsFallback")
    private String fetchAndExtractPatterns(String repoFullName) {
        log.info("[ACT] Loading team review patterns from last {} PRs of {}", historyPrCount, repoFullName);

        // Fetch last N closed PRs
        String prsJson = gitHubRestClient.get()
                .uri("/repos/{repo}/pulls?state=closed&sort=updated&direction=desc&per_page={n}",
                        repoFullName, historyPrCount)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .body(String.class);

        List<Integer> prNumbers;
        try {
            JsonNode prsNode = objectMapper.readTree(prsJson);
            prNumbers = StreamSupport.stream(prsNode.spliterator(), false)
                    .map(n -> n.path("number").asInt())
                    .filter(n -> n > 0)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not parse closed PRs list: {}", e.getMessage());
            return "";
        }

        if (prNumbers.isEmpty()) {
            log.info("No closed PRs found for {} -- skipping history", repoFullName);
            return "";
        }

        // Collect review comments from each PR (best-effort, skip failures)
        List<String> allComments = new ArrayList<>();
        for (int prNumber : prNumbers) {
            try {
                allComments.addAll(fetchReviewComments(repoFullName, prNumber));
            } catch (Exception e) {
                log.debug("Skipping PR #{} review comments: {}", prNumber, e.getMessage());
            }
            if (allComments.size() >= 200) break; // cap to avoid huge prompts
        }

        if (allComments.isEmpty()) {
            log.info("No review comments found in history for {}", repoFullName);
            return "";
        }

        log.info("[OBSERVE] Collected {} historical review comments from {}", allComments.size(), repoFullName);
        String patterns = extractPatterns(allComments);
        log.info("[OBSERVE] Team patterns extracted ({} chars)", patterns.length());
        return patterns;
    }

    @SuppressWarnings("unused")
    private String patternsFallback(String repoFullName, Throwable t) {
        log.warn("Review history unavailable for {} -- proceeding without team context: {}",
                repoFullName, t.getMessage());
        return "";
    }

    private List<String> fetchReviewComments(String repoFullName, int prNumber) throws Exception {
        String commentsJson = gitHubRestClient.get()
                .uri("/repos/{repo}/pulls/{pr}/comments?per_page=50", repoFullName, prNumber)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .body(String.class);

        List<Map<String, Object>> comments = objectMapper.readValue(
                commentsJson, new TypeReference<>() {});

        return comments.stream()
                .map(c -> String.valueOf(c.getOrDefault("body", "")))
                .filter(b -> !b.isBlank() && b.length() > 20) // skip trivial comments
                .toList();
    }

    private String extractPatterns(List<String> comments) {
        // Limit total input size to avoid token overflow
        String joined = comments.stream()
                .limit(100)
                .map(c -> "- " + c.replace("\n", " ").substring(0, Math.min(c.length(), 300)))
                .reduce("", (a, b) -> a + "\n" + b);

        String prompt = """
                You are analysing a team's past code review comments to understand their coding standards.

                Past review comments from this team:
                %s

                Identify the TOP 10 patterns this team consistently flags in code reviews.
                Focus on recurring themes, not one-off comments.

                Format as a numbered list of concise rules, e.g.:
                1. Always use parameterised logging -- never string concatenation in log statements
                2. Every public method must have Javadoc
                3. Prefer Optional over null returns
                ...

                Output ONLY the numbered list -- no preamble.
                """.formatted(joined);

        var options = new ChatCompletionsOptions(List.of(new ChatRequestUserMessage(prompt)));
        options.setMaxTokens(600);
        options.setTemperature(0.1);

        return openAIClient.getChatCompletions(deployment, options)
                .getChoices().get(0).getMessage().getContent();
    }
}
