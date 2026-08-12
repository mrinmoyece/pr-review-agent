package com.agentforge.prreview.controller;

import com.agentforge.prreview.agent.PRReviewAgent;
import com.agentforge.prreview.model.ReviewResult;
import com.agentforge.prreview.security.WebhookDeliveryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * GitHub Webhook receiver.
 *
 * Listens for pull_request events (opened, synchronize, reopened) and
 * triggers the PR review pipeline. Validates the HMAC-SHA256 signature
 * on every request to prevent spoofed webhook delivery.
 *
 * GitHub delivers webhooks at: POST /webhook/github
 */
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private static final Pattern REPOSITORY_NAME = Pattern.compile(
            "[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");

    private final PRReviewAgent prReviewAgent;
    private final ObjectMapper objectMapper;
    private final WebhookDeliveryStore webhookDeliveryStore;

    @Value("${github.webhook.secret}")
    private String webhookSecret;

    @Value("${github.repository-allowlist:}")
    private String repositoryAllowlist;

    @Value("${review.webhook.max-payload-bytes:10485760}")
    private int maxPayloadBytes;

    @Value("${review.manual-trigger.enabled:false}")
    private boolean manualTriggerEnabled;

    @Value("${review.manual-trigger.token:}")
    private String manualTriggerToken;

    @PostConstruct
    void validateConfiguration() {
        if (webhookSecret == null
                || webhookSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("GITHUB_WEBHOOK_SECRET must contain at least 32 bytes");
        }
        if (allowedRepositories().isEmpty()) {
            throw new IllegalStateException("GITHUB_REPOSITORY_ALLOWLIST must contain at least one repository");
        }
        if (manualTriggerEnabled
                && manualTriggerToken.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "REVIEW_MANUAL_TRIGGER_TOKEN must contain at least 32 bytes when enabled");
        }
    }

    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> handleGitHubWebhook(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestBody String payload) {

        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            return ResponseEntity.status(413).body(Map.of("error", "Payload too large"));
        }

        if (!isValidSignature(payload, signature)) {
            log.warn("Invalid webhook signature - rejecting request");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid signature"));
        }

        if (!"pull_request".equals(event)) {
            log.debug("Ignoring event type: {}", event);
            return ResponseEntity.ok(Map.of("status", "ignored", "event", event));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("Malformed webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed JSON payload"));
        }

        String action = textOrNull(root, "action");
        if (action == null || !java.util.Set.of("opened", "synchronize", "reopened").contains(action)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "action", action == null ? "unknown" : action));
        }

        String repo = textOrNull(root.path("repository"), "full_name");
        JsonNode prNumberNode = root.path("pull_request").path("number");

        if (repo == null || !REPOSITORY_NAME.matcher(repo).matches() || prNumberNode.isMissingNode()) {
            log.error("Could not parse repo/PR number from webhook payload");
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid payload"));
        }
        if (!allowedRepositories().contains(repo)) {
            log.warn("Rejected webhook for repository outside allowlist: {}", repo);
            return ResponseEntity.status(403).body(Map.of("error", "Repository not allowed"));
        }

        int prNumber;
        try {
            prNumber = prNumberNode.isInt() ? prNumberNode.intValue() : Integer.parseInt(prNumberNode.asText());
        } catch (NumberFormatException e) {
            log.error("Could not parse PR number from webhook payload: {}", prNumberNode.asText());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid PR number in payload"));
        }

        String headBranch = textOrNull(root.path("pull_request").path("head"), "ref");
        String headRepository = textOrNull(
                root.path("pull_request").path("head").path("repo"), "full_name");
        String prTitle = textOrNull(root.path("pull_request"), "title");
        String prBody = textOrNull(root.path("pull_request"), "body");
        boolean sameRepositoryPullRequest = repo.equals(headRepository);

        if (prNumber < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid PR number in payload"));
        }
        String replayKey = authenticatedReplayKey(payload);
        if (deliveryId.isBlank() || !webhookDeliveryStore.recordIfNew(replayKey)) {
            log.warn("Duplicate or missing webhook delivery");
            return ResponseEntity.status(409).body(Map.of("error", "Duplicate delivery"));
        }

        log.info("PR {} #{} received - triggering review", repo, prNumber);

        try {
            CompletableFuture<ReviewResult> future = prReviewAgent.review(repo, prNumber,
                    sameRepositoryPullRequest && headBranch != null ? headBranch : "",
                    prTitle != null ? prTitle : "",
                    prBody != null ? prBody : "");
            future.whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("Review failed for {}/#{}: {}",
                            repo, prNumber, sanitizeForLog(failure.getMessage()), failure);
                    releaseFailedDelivery(replayKey, deliveryId);
                }
            });
        } catch (RuntimeException e) {
            releaseFailedDelivery(replayKey, deliveryId);
            throw e;
        }

        return ResponseEntity.accepted()
                .body(Map.of("status", "review_triggered", "pr", prNumber + ""));
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> manualTrigger(
            @RequestHeader(value = "X-Review-Trigger-Token", defaultValue = "") String triggerToken,
            @RequestParam String repo,
            @RequestParam int pr) {
        if (!manualTriggerEnabled) {
            return ResponseEntity.notFound().build();
        }
        if (manualTriggerToken.isBlank() || !constantTimeEquals(manualTriggerToken, triggerToken)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid trigger token"));
        }
        if (!REPOSITORY_NAME.matcher(repo).matches() || !allowedRepositories().contains(repo) || pr < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid repository or PR"));
        }
        log.info("Manual review trigger for {}/#{}", repo, pr);
        prReviewAgent.review(repo, pr, "", "", "");
        return ResponseEntity.accepted()
                .body(Map.of("status", "review_triggered", "repo", repo, "pr", String.valueOf(pr)));
    }

    private boolean isValidSignature(String payload, String signatureHeader) {
        if (signatureHeader == null
                || !signatureHeader.matches("sha256=[0-9a-f]{64}")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return constantTimeEquals(expected, signatureHeader);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private Set<String> allowedRepositories() {
        if (repositoryAllowlist == null || repositoryAllowlist.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(repositoryAllowlist.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\r\\n\\t\\f\\u0000-\\u001F\\u007F]", "_");
    }

    private String authenticatedReplayKey(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void releaseFailedDelivery(String replayKey, String deliveryId) {
        try {
            webhookDeliveryStore.release(replayKey);
        } catch (RuntimeException releaseFailure) {
            log.error("Could not release failed webhook delivery {}: {}",
                    sanitizeForLog(deliveryId),
                    sanitizeForLog(releaseFailure.getMessage()), releaseFailure);
        }
    }
}
