package com.agentforge.prreview.controller;

import com.agentforge.prreview.agent.PRReviewAgent;
import com.agentforge.prreview.model.ReviewResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    private final PRReviewAgent prReviewAgent;
    private final ObjectMapper objectMapper;

    @Value("${github.webhook.secret}")
    private String webhookSecret;

    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> handleGitHubWebhook(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody String payload) {

        // 1. Validate HMAC-SHA256 signature — reject unauthenticated requests
        if (!isValidSignature(payload, signature)) {
            log.warn("Invalid webhook signature — rejecting request");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid signature"));
        }

        // 2. Only process pull_request events
        if (!"pull_request".equals(event)) {
            log.debug("Ignoring event type: {}", event);
            return ResponseEntity.ok(Map.of("status", "ignored", "event", event));
        }

        // 3. Parse PR details from payload via Jackson
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("Malformed webhook payload — not valid JSON: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Malformed JSON payload"));
        }

        String action = textOrNull(root, "action");
        if (action == null || !java.util.Set.of("opened", "synchronize", "reopened").contains(action)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "action", action == null ? "unknown" : action));
        }

        String repo = textOrNull(root.path("repository"), "full_name");
        JsonNode prNumberNode = root.path("pull_request").path("number");

        if (repo == null || prNumberNode.isMissingNode()) {
            log.error("Could not parse repo/PR number from webhook payload");
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid payload"));
        }

        int prNumber;
        try {
            prNumber = prNumberNode.isInt() ? prNumberNode.intValue() : Integer.parseInt(prNumberNode.asText());
        } catch (NumberFormatException e) {
            log.error("Could not parse PR number from webhook payload: {}", prNumberNode.asText());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid PR number in payload"));
        }

        String headBranch = textOrNull(root.path("pull_request").path("head"), "ref");
        String prTitle = textOrNull(root.path("pull_request"), "title");
        String prBody = textOrNull(root.path("pull_request"), "body");

        log.info("PR {} #{} received — triggering review", repo, prNumber);

        // 4. Trigger async review — return 202 immediately (GitHub requires fast ACK)
        CompletableFuture<ReviewResult> future = prReviewAgent.review(repo, prNumber,
                headBranch != null ? headBranch : "main",
                prTitle != null ? prTitle : "",
                prBody != null ? prBody : "");
        future.exceptionally(ex -> {
            log.error("Review failed for {}/#{}: {}", repo, prNumber, ex.getMessage(), ex);
            return null;
        });

        return ResponseEntity.accepted()
                .body(Map.of("status", "review_triggered", "pr", prNumber + ""));
    }

    /**
     * Health endpoint for manual review trigger (useful in testing).
     * POST /webhook/trigger?repo=org/repo&pr=42
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> manualTrigger(
            @RequestParam String repo,
            @RequestParam int pr) {
        log.info("Manual review trigger for {}/#{}", repo, pr);
        prReviewAgent.review(repo, pr, "main", "", "");
        return ResponseEntity.accepted()
                .body(Map.of("status", "review_triggered", "repo", repo, "pr", String.valueOf(pr)));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private boolean isValidSignature(String payload, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(expected, signatureHeader);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
