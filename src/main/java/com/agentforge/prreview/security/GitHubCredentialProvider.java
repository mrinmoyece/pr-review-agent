package com.agentforge.prreview.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/**
 * Supplies GitHub API credentials, refreshing GitHub App installation tokens before expiry.
 */
@Component
@RequiredArgsConstructor
public class GitHubCredentialProvider {

    private static final long REFRESH_SKEW_SECONDS = 300;
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final RestClient gitHubRestClient;
    private final ObjectMapper objectMapper;

    @Value("${github.auth.mode:token}")
    private String authMode;

    @Value("${github.token:}")
    private String staticToken;

    @Value("${github.app.id:}")
    private String appId;

    @Value("${github.app.installation-id:}")
    private String installationId;

    @Value("${github.app.private-key:}")
    private String privateKeyPem;

    private volatile String installationToken;
    private volatile Instant refreshAt = Instant.EPOCH;
    private PrivateKey privateKey;

    @PostConstruct
    void validateConfiguration() {
        switch (authMode) {
            case "token" -> require(staticToken, "GITHUB_TOKEN");
            case "app" -> {
                require(appId, "GITHUB_APP_ID");
                require(installationId, "GITHUB_APP_INSTALLATION_ID");
                require(privateKeyPem, "GITHUB_APP_PRIVATE_KEY");
                privateKey = parsePrivateKey(privateKeyPem);
            }
            default -> throw new IllegalStateException("Unsupported GITHUB_AUTH_MODE: " + authMode);
        }
    }

    public String token() {
        if ("token".equals(authMode)) {
            return staticToken;
        }
        Instant now = Instant.now();
        if (installationToken == null || !now.isBefore(refreshAt)) {
            synchronized (this) {
                now = Instant.now();
                if (installationToken == null || !now.isBefore(refreshAt)) {
                    refreshInstallationToken(now);
                }
            }
        }
        return installationToken;
    }

    private void refreshInstallationToken(Instant now) {
        String jwt = createAppJwt(now);
        String response = gitHubRestClient.post()
                .uri("/app/installations/{installationId}/access_tokens", installationId)
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            String token = root.path("token").asText();
            Instant expiresAt = Instant.parse(root.path("expires_at").asText());
            if (token.isBlank() || !expiresAt.isAfter(now.plusSeconds(REFRESH_SKEW_SECONDS))) {
                throw new IllegalStateException("GitHub returned an invalid installation token");
            }
            installationToken = token;
            refreshAt = expiresAt.minusSeconds(REFRESH_SKEW_SECONDS);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse GitHub installation token response", e);
        }
    }

    private String createAppJwt(Instant now) {
        String header = encodeJson(Map.of("alg", "RS256", "typ", "JWT"));
        String claims = encodeJson(Map.of(
                "iat", now.minusSeconds(60).getEpochSecond(),
                "exp", now.plusSeconds(540).getEpochSecond(),
                "iss", appId));
        String signingInput = header + "." + claims;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64_URL.encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign GitHub App JWT", e);
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode GitHub App JWT", e);
        }
    }

    private PrivateKey parsePrivateKey(String pem) {
        try {
            String pkcs8Begin = pemMarker("BEGIN", "");
            String pkcs8End = pemMarker("END", "");
            String pkcs1Begin = pemMarker("BEGIN", "RSA");
            String pkcs1End = pemMarker("END", "RSA");
            boolean pkcs1 = pem.contains(pkcs1Begin);
            String normalized = pem.replace("\\n", "\n")
                    .replace(pkcs8Begin, "")
                    .replace(pkcs8End, "")
                    .replace(pkcs1Begin, "")
                    .replace(pkcs1End, "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalized);
            if (pkcs1) {
                keyBytes = wrapPkcs1AsPkcs8(keyBytes);
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "GITHUB_APP_PRIVATE_KEY must be a PEM-encoded RSA private key", e);
        }
    }

    private String pemMarker(String boundary, String keyType) {
        String typePrefix = keyType.isBlank() ? "" : keyType + " ";
        return "-----" + boundary + " " + typePrefix + "PRIVATE" + " KEY-----";
    }

    private byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        byte[] rsaAlgorithmIdentifier = {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] {0x02, 0x01, 0x00});
        body.writeBytes(rsaAlgorithmIdentifier);
        body.write(0x04);
        writeDerLength(body, pkcs1.length);
        body.writeBytes(pkcs1);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(0x30);
        writeDerLength(result, body.size());
        result.writeBytes(body.toByteArray());
        return result.toByteArray();
    }

    private void writeDerLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }
        int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        output.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            output.write(length >> shift);
        }
    }

    private void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is required for GitHub " + authMode + " authentication");
        }
    }
}
