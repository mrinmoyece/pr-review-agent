package com.agentforge.prreview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubCredentialProviderTest {

    @Test
    void staticTokenModeReturnsConfiguredToken() {
        GitHubCredentialProvider provider = provider(RestClient.create());
        ReflectionTestUtils.setField(provider, "authMode", "token");
        ReflectionTestUtils.setField(provider, "staticToken", "configured-token");

        provider.validateConfiguration();

        assertThat(provider.token()).isEqualTo("configured-token");
    }

    @Test
    void appModeMintsAndCachesInstallationToken() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubCredentialProvider provider = provider(builder.build());
        ReflectionTestUtils.setField(provider, "authMode", "app");
        ReflectionTestUtils.setField(provider, "appId", "123");
        ReflectionTestUtils.setField(provider, "installationId", "456");
        ReflectionTestUtils.setField(provider, "privateKeyPem", privateKeyPem());
        server.expect(requestTo("https://api.github.test/app/installations/456/access_tokens"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"token":"installation-token","expires_at":"%s"}
                        """.formatted(Instant.now().plusSeconds(3600)),
                        org.springframework.http.MediaType.APPLICATION_JSON));

        provider.validateConfiguration();

        assertThat(provider.token()).isEqualTo("installation-token");
        assertThat(provider.token()).isEqualTo("installation-token");
        server.verify();
    }

    @Test
    void appModeAcceptsGitHubDownloadedPkcs1Key() throws Exception {
        GitHubCredentialProvider provider = provider(RestClient.create());
        ReflectionTestUtils.setField(provider, "authMode", "app");
        ReflectionTestUtils.setField(provider, "appId", "123");
        ReflectionTestUtils.setField(provider, "installationId", "456");
        ReflectionTestUtils.setField(provider, "privateKeyPem", pkcs1PrivateKeyPem());

        provider.validateConfiguration();
    }

    private GitHubCredentialProvider provider(RestClient restClient) {
        return new GitHubCredentialProvider(restClient, new ObjectMapper());
    }

    private String privateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }

    private String pkcs1PrivateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] pkcs1 = extractPrivateKeyOctets(generator.generateKeyPair().getPrivate().getEncoded());
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs1);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + encoded + "\n-----END RSA PRIVATE KEY-----";
    }

    private byte[] extractPrivateKeyOctets(byte[] pkcs8) {
        int[] offset = {0};
        skipTagAndLength(pkcs8, offset, 0x30);
        skipValue(pkcs8, offset, 0x02);
        skipValue(pkcs8, offset, 0x30);
        if ((pkcs8[offset[0]++] & 0xff) != 0x04) {
            throw new IllegalArgumentException("Expected private-key octet string");
        }
        int length = readLength(pkcs8, offset);
        return Arrays.copyOfRange(pkcs8, offset[0], offset[0] + length);
    }

    private void skipValue(byte[] der, int[] offset, int expectedTag) {
        if ((der[offset[0]++] & 0xff) != expectedTag) {
            throw new IllegalArgumentException("Unexpected DER tag");
        }
        int length = readLength(der, offset);
        offset[0] += length;
    }

    private void skipTagAndLength(byte[] der, int[] offset, int expectedTag) {
        if ((der[offset[0]++] & 0xff) != expectedTag) {
            throw new IllegalArgumentException("Unexpected DER tag");
        }
        readLength(der, offset);
    }

    private int readLength(byte[] der, int[] offset) {
        int first = der[offset[0]++] & 0xff;
        if (first < 128) {
            return first;
        }
        int count = first & 0x7f;
        int length = 0;
        for (int i = 0; i < count; i++) {
            length = (length << 8) | (der[offset[0]++] & 0xff);
        }
        return length;
    }
}
