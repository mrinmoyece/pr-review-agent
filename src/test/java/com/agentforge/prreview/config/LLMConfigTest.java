package com.agentforge.prreview.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LLMConfigTest {

    @Test
    void azureProviderDoesNotFallBackWhenAzureCredentialIsMissing() {
        LLMConfig config = configured("azure_openai");
        ReflectionTestUtils.setField(config, "azureEndpoint", "https://example.openai.azure.com");
        ReflectionTestUtils.setField(config, "azureKey", "");
        ReflectionTestUtils.setField(config, "githubModelsToken", "github-token");

        assertThatThrownBy(config::openAIClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_OPENAI_API_KEY");
    }

    @Test
    void unknownProviderIsRejected() {
        LLMConfig config = configured("unexpected");

        assertThatThrownBy(config::openAIClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported LLM_PROVIDER");
    }

    private LLMConfig configured(String provider) {
        LLMConfig config = new LLMConfig();
        ReflectionTestUtils.setField(config, "provider", provider);
        ReflectionTestUtils.setField(config, "azureEndpoint", "");
        ReflectionTestUtils.setField(config, "azureKey", "");
        ReflectionTestUtils.setField(config, "githubModelsEndpoint",
                "https://models.inference.ai.azure.com");
        ReflectionTestUtils.setField(config, "githubModelsToken", "");
        return config;
    }
}
