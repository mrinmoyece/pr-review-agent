package com.agentforge.prreview.controller;

import com.agentforge.prreview.agent.PRReviewAgent;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.security.WebhookDeliveryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    private static final String SECRET = "webhook-test-value".repeat(3);
    private static final String REPOSITORY = "org/repo";

    private PRReviewAgent reviewAgent;
    private WebhookDeliveryStore deliveryStore;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        reviewAgent = mock(PRReviewAgent.class);
        deliveryStore = mock(WebhookDeliveryStore.class);
        controller = new WebhookController(reviewAgent, new ObjectMapper(), deliveryStore);
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
        ReflectionTestUtils.setField(controller, "repositoryAllowlist", REPOSITORY);
        ReflectionTestUtils.setField(controller, "maxPayloadBytes", 1_000_000);
        ReflectionTestUtils.setField(controller, "manualTriggerEnabled", false);
        ReflectionTestUtils.setField(controller, "manualTriggerToken", "");
        controller.validateConfiguration();
        when(reviewAgent.review(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(ReviewResult.class)));
        when(deliveryStore.recordIfNew(anyString())).thenReturn(true);
    }

    @Test
    void validWebhookForAllowedRepositoryTriggersReview() throws Exception {
        String payload = payload(REPOSITORY);

        var response = controller.handleGitHubWebhook(
                "pull_request", signature(payload), "delivery-1", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(reviewAgent).review(REPOSITORY, 42, "feature", "Improve checks", "Details");
    }

    @Test
    void duplicateDeliveryIsRejected() throws Exception {
        String payload = payload(REPOSITORY);
        String signature = signature(payload);

        controller.handleGitHubWebhook("pull_request", signature, "delivery-2", payload);
        when(deliveryStore.recordIfNew("delivery-2")).thenReturn(false);
        var duplicate = controller.handleGitHubWebhook(
                "pull_request", signature, "delivery-2", payload);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void repositoryOutsideAllowlistIsRejected() throws Exception {
        String payload = payload("attacker/repo");

        var response = controller.handleGitHubWebhook(
                "pull_request", signature(payload), "delivery-3", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void manualTriggerIsNotExposedByDefault() {
        var response = controller.manualTrigger("", REPOSITORY, 42);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String payload(String repository) {
        return """
                {
                  "action": "opened",
                  "repository": {"full_name": "%s"},
                  "pull_request": {
                    "number": 42,
                    "title": "Improve checks",
                    "body": "Details",
                    "head": {
                      "ref": "feature",
                      "repo": {"full_name": "%s"}
                    }
                  }
                }
                """.formatted(repository, repository);
    }

    private String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
