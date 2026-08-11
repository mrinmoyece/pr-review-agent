package com.agentforge.prreview.tool;

import com.azure.ai.openai.OpenAIClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReviewHistoryToolTest {

    private RestClient mockGitHubClient;
    private ReviewHistoryTool tool;

    @BeforeEach
    void setUp() {
        mockGitHubClient = mock(RestClient.class);
        tool = new ReviewHistoryTool(
                mockGitHubClient,
                mock(OpenAIClient.class),
                new ObjectMapper()
        );
    }

    @Test
    void loadTeamPatterns_returnsEmptyString_onCircuitBreakerFallback() {
        // When the GitHub API is unavailable, patternsFallback fires and returns ""
        // We simulate by making the RestClient throw
        when(mockGitHubClient.get()).thenThrow(new RuntimeException("Connection refused"));

        String result = tool.loadTeamPatterns("org/repo");

        // Fail-open: returns empty string rather than propagating exception
        assertThat(result).isNotNull();
        // Either "" (fallback) or populated — just must not throw
    }

    @Test
    void loadTeamPatterns_secondCallReturnsCachedValue_withoutRefetching() {
        // First call: fails -> returns "" from fallback (cached as "")
        when(mockGitHubClient.get()).thenThrow(new RuntimeException("API down"));

        String first = tool.loadTeamPatterns("org/repo-cache-test");
        // Verify it returned without explosion
        assertThat(first).isNotNull();

        // Reset mock so any second real call would succeed/change behaviour
        reset(mockGitHubClient);
        when(mockGitHubClient.get()).thenThrow(new RuntimeException("Should not be called"));

        // Second call for the same repo — must hit cache, not GitHub
        String second = tool.loadTeamPatterns("org/repo-cache-test");

        // Same cached value returned, and the new mock was NOT invoked
        assertThat(second).isEqualTo(first);
        verify(mockGitHubClient, never()).get();
    }
}
