package com.agentforge.prreview.tool;

import com.agentforge.prreview.model.TicketAlignment;
import com.azure.ai.openai.OpenAIClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JiraTicketToolTest {

    private JiraTicketTool tool;

    @BeforeEach
    void setUp() {
        tool = new JiraTicketTool(mock(OpenAIClient.class), new ObjectMapper());
        // Leave jiraBaseUrl and jiraToken blank (their @Value defaults) so tests stay fast
    }

    @Test
    void extractTicketKey_findsKeyInPrTitle() {
        String key = tool.extractTicketKey("PROJ-123: Fix login bug", "");
        assertThat(key).isEqualTo("PROJ-123");
    }

    @Test
    void extractTicketKey_findsKeyInPrBody_whenTitleHasNone() {
        String key = tool.extractTicketKey("Fix login bug", "Relates to BACKEND-42 in the sprint.");
        assertThat(key).isEqualTo("BACKEND-42");
    }

    @Test
    void extractTicketKey_prefersTitle_whenBothHaveKey() {
        String key = tool.extractTicketKey("FRONT-1: Fix login", "See also BACK-99.");
        // Title is checked first, so FRONT-1 wins
        assertThat(key).isEqualTo("FRONT-1");
    }

    @Test
    void extractTicketKey_returnsNull_whenNoKeyPresent() {
        String key = tool.extractTicketKey("Fix login bug", "No ticket mentioned here.");
        assertThat(key).isNull();
    }

    @Test
    void checkAlignment_returnsEmptyOptional_whenJiraNotConfigured() {
        // jiraBaseUrl is blank by default after @BeforeEach (no @Value injection in unit test)
        // We must set it explicitly to blank to exercise the guard
        ReflectionTestUtils.setField(tool, "jiraBaseUrl", "");
        ReflectionTestUtils.setField(tool, "jiraToken", "");

        Optional<TicketAlignment> result = tool.checkAlignment("PROJ-1: something", "body", "diff");

        assertThat(result).isEmpty();
    }

    @Test
    void checkAlignment_returnsEmptyOptional_whenNoTicketKeyFound() {
        // Configure Jira URL so the guard passes, but PR has no ticket key
        ReflectionTestUtils.setField(tool, "jiraBaseUrl", "https://company.atlassian.net");
        ReflectionTestUtils.setField(tool, "jiraToken", "token");
        ReflectionTestUtils.setField(tool, "jiraUserEmail", "user@example.com");

        Optional<TicketAlignment> result = tool.checkAlignment("Fix login bug", "No ticket here", "diff");

        assertThat(result).isEmpty();
    }
}
