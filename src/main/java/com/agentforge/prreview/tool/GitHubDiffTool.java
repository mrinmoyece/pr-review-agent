package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.DiffFile;
import com.agentforge.prreview.exception.ReviewAgentException;
import com.agentforge.prreview.security.GitHubCredentialProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches raw PR diffs from the GitHub REST API and parses them into
 * structured DiffFile objects for downstream analysis tools.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubDiffTool {

    private final RestClient gitHubRestClient;
    private final GitHubCredentialProvider credentialProvider;

    @Retry(name = "github-read")
    @CircuitBreaker(name = "github", fallbackMethod = "fetchDiffFallback")
    public String fetchDiff(String repoFullName, int prNumber, String headSha) {
        log.info("Fetching diff for {}/#{} (expected head {})", repoFullName, prNumber, headSha);
        return gitHubRestClient.get()
                .uri("/repos/{repo}/pulls/{pr}", repoFullName, prNumber)
                .header("Authorization", "Bearer " + credentialProvider.token())
                .header("Accept", "application/vnd.github.diff")
                .retrieve()
                .body(String.class);
    }

    /**
     * Returns the current head SHA of the pull request from GitHub.
     * Used to detect whether the PR has been updated since the webhook was received.
     */
    @Retry(name = "github-read")
    @CircuitBreaker(name = "github", fallbackMethod = "fetchHeadShaFallback")
    public String fetchCurrentHeadSha(String repoFullName, int prNumber) {
        String json = gitHubRestClient.get()
                .uri("/repos/{repo}/pulls/{pr}", repoFullName, prNumber)
                .header("Authorization", "Bearer " + credentialProvider.token())
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(String.class);
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String sha = node.path("head").path("sha").asText(null);
            if (sha == null || sha.isBlank()) {
                throw new ReviewAgentException("GitHub PR response missing head.sha");
            }
            return sha;
        } catch (ReviewAgentException e) {
            throw e;
        } catch (Exception e) {
            throw new ReviewAgentException("Could not parse head.sha from PR response", e);
        }
    }

    public String fetchDiffFallback(String repoFullName, int prNumber, String headSha, Throwable t) {
        throw new ReviewAgentException(
                "GitHub API unavailable for " + repoFullName + "/#" + prNumber, t);
    }

    public String fetchHeadShaFallback(String repoFullName, int prNumber, Throwable t) {
        throw new ReviewAgentException(
                "GitHub API unavailable (head SHA check) for " + repoFullName + "/#" + prNumber, t);
    }

    /**
     * Parses a unified diff string into DiffFile objects.
     * Only files with actual code changes are included; binary files are skipped.
     */
    public List<DiffFile> parseDiff(String rawDiff) {
        List<DiffFile> files = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) return files;

        String[] sections = rawDiff.split("(?=diff --git )");
        for (String section : sections) {
            if (section.isBlank()) continue;
            String[] lines = section.split("\n");
            String filename = extractFilename(lines);
            if (filename == null) continue;

            List<String> addedLines   = new ArrayList<>();
            List<String> removedLines = new ArrayList<>();
            StringBuilder rawContent  = new StringBuilder();

            for (String line : lines) {
                rawContent.append(line).append("\n");
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    addedLines.add(line.substring(1));
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    removedLines.add(line.substring(1));
                }
            }

            files.add(DiffFile.builder()
                    .filename(filename)
                    .language(detectLanguage(filename))
                    .addedLines(addedLines)
                    .removedLines(removedLines)
                    .rawDiff(rawContent.toString())
                    .linesAdded(addedLines.size())
                    .linesRemoved(removedLines.size())
                    .build());
        }
        log.debug("Parsed {} changed files from diff", files.size());
        return files;
    }

    private String extractFilename(String[] lines) {
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 4) {
                    return parts[3].replaceFirst("^b/", "");
                }
            }
        }
        return null;
    }

    private String detectLanguage(String filename) {
        if (filename.endsWith(".java"))       return "java";
        if (filename.endsWith(".kt"))         return "kotlin";
        if (filename.endsWith(".py"))         return "python";
        if (filename.endsWith(".ts"))         return "typescript";
        if (filename.endsWith(".js"))         return "javascript";
        if (filename.endsWith(".go"))         return "go";
        if (filename.endsWith(".yaml") || filename.endsWith(".yml")) return "yaml";
        if (filename.endsWith(".sql"))        return "sql";
        if (filename.endsWith(".xml"))        return "xml";
        return "unknown";
    }
}
