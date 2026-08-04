package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.AutoFix;
import com.agentforge.prreview.model.ReviewComment;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Applies LLM-generated fixes for auto-fixable, low-risk review comments directly
 * to the PR branch via GitHub Contents API.
 *
 * Safety boundary: only LOW/MEDIUM autoFixable comments are processed.
 * CRITICAL and HIGH issues are never auto-committed -- they require human review.
 *
 * GitHub Contents API flow:
 *   1. GET /repos/{repo}/contents/{path}?ref={branch} -> get current SHA + base64 content
 *   2. LLM generates corrected file content
 *   3. PUT /repos/{repo}/contents/{path} with {message, content (base64), sha, branch}
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubAutoFixTool {

    private final RestClient gitHubRestClient;
    private final OpenAIClient openAIClient;
    private final ObjectMapper objectMapper;

    @Value("${github.token}")
    private String githubToken;

    @Value("${llm.chat-deployment:gpt-4o}")
    private String deployment;

    /**
     * Attempts to auto-fix all eligible comments.
     * Returns a list of AutoFix records describing what was committed (or skipped).
     */
    public List<AutoFix> applyFixes(String repoFullName, String branchRef,
                                     List<ReviewComment> comments) {
        List<ReviewComment> eligible = comments.stream()
                .filter(c -> c.isAutoFixable()
                        && (c.getSeverity() == ReviewComment.CommentSeverity.LOW
                            || c.getSeverity() == ReviewComment.CommentSeverity.MEDIUM))
                .toList();

        if (eligible.isEmpty()) {
            log.info("No auto-fixable comments for {}", repoFullName);
            return List.of();
        }

        log.info("[ACT] Applying {} auto-fixes to branch {} of {}", eligible.size(), branchRef, repoFullName);

        List<AutoFix> applied = new ArrayList<>();

        // Group by filename so we patch each file at most once
        Map<String, List<ReviewComment>> byFile = new LinkedHashMap<>();
        for (ReviewComment c : eligible) {
            if (c.getFilename() != null) {
                byFile.computeIfAbsent(c.getFilename(), k -> new ArrayList<>()).add(c);
            }
        }

        for (Map.Entry<String, List<ReviewComment>> entry : byFile.entrySet()) {
            String filename = entry.getKey();
            List<ReviewComment> fileComments = entry.getValue();
            try {
                AutoFix fix = fixFile(repoFullName, branchRef, filename, fileComments);
                applied.add(fix);
            } catch (Exception e) {
                log.warn("Auto-fix skipped for {} -- {}", filename, e.getMessage());
                applied.add(AutoFix.skipped(filename, e.getMessage()));
            }
        }

        log.info("[OBSERVE] Auto-fix: {} committed, {} skipped",
                applied.stream().filter(AutoFix::isApplied).count(),
                applied.stream().filter(f -> !f.isApplied()).count());

        return applied;
    }

    @Retry(name = "github")
    @CircuitBreaker(name = "github", fallbackMethod = "fixFileFallback")
    private AutoFix fixFile(String repoFullName, String branchRef,
                             String filename, List<ReviewComment> comments) {
        // Step 1: Fetch current file from GitHub
        String fileJson = gitHubRestClient.get()
                .uri("/repos/{repo}/contents/{path}?ref={ref}", repoFullName, filename, branchRef)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .body(String.class);

        JsonNode fileNode;
        try {
            fileNode = objectMapper.readTree(fileJson);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse file metadata for " + filename, e);
        }

        String currentSha = fileNode.get("sha").asText();
        String base64Content = fileNode.get("content").asText().replace("\n", "");
        String currentContent = new String(Base64.getDecoder().decode(base64Content));

        // Step 2: LLM generates the corrected file
        String issueList = comments.stream()
                .map(c -> "- [%s] Line %s: %s -- %s".formatted(
                        c.getSeverity(), c.getLineNumber(), c.getTitle(), c.getBody()))
                .reduce("", (a, b) -> a + "\n" + b);

        String systemPrompt = """
                You are a code fixer. Apply ONLY the specified style/quality fixes to the file.
                Do not change logic, do not rename methods, do not reorganise structure.
                Return the COMPLETE corrected file content -- no explanations, no markdown fences.
                """;
        String userPrompt = """
                File: %s
                Issues to fix (LOW/MEDIUM style issues only):
                %s

                Current file content:
                ```
                %s
                ```

                Return the corrected file content:
                """.formatted(filename, issueList, currentContent);

        var options = new ChatCompletionsOptions(List.of(
                new ChatRequestSystemMessage(systemPrompt),
                new ChatRequestUserMessage(userPrompt)
        ));
        options.setMaxTokens(4096);
        options.setTemperature(0.1);

        String fixedContent = openAIClient.getChatCompletions(deployment, options)
                .getChoices().get(0).getMessage().getContent().strip();

        // Step 3: Commit the fix to the branch
        String commitMessage = "fix(review-agent): auto-fix %d style issue(s) in %s".formatted(
                comments.size(), filename.substring(filename.lastIndexOf('/') + 1));

        Map<String, Object> body = new HashMap<>();
        body.put("message", commitMessage);
        body.put("content", Base64.getEncoder().encodeToString(fixedContent.getBytes()));
        body.put("sha", currentSha);
        body.put("branch", branchRef);

        String responseJson = gitHubRestClient.put()
                .uri("/repos/{repo}/contents/{path}", repoFullName, filename)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .body(body)
                .retrieve()
                .body(String.class);

        String commitSha;
        try {
            commitSha = objectMapper.readTree(responseJson).path("commit").path("sha").asText("unknown");
        } catch (Exception e) {
            commitSha = "unknown";
        }

        log.info("[OBSERVE] Auto-fix committed {} -> sha:{}", filename, commitSha);
        return AutoFix.committed(filename, commitSha, commitMessage, comments.size());
    }

    @SuppressWarnings("unused")
    private AutoFix fixFileFallback(String repoFullName, String branchRef,
                                     String filename, List<ReviewComment> comments, Throwable t) {
        log.warn("GitHub circuit breaker open -- auto-fix skipped for {}: {}", filename, t.getMessage());
        return AutoFix.skipped(filename, "GitHub API unavailable: " + t.getMessage());
    }
}
