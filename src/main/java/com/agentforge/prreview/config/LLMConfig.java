package com.agentforge.prreview.config;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.KeyCredential;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * LLM and HTTP client configuration.
 * Supports Azure OpenAI (production) and GitHub Models (dev/free tier).
 */
@Configuration
@Slf4j
public class LLMConfig {

    @Value("${llm.provider:github_models}")
    private String provider;

    @Value("${azure.openai.endpoint:}")
    private String azureEndpoint;

    @Value("${azure.openai.key:}")
    private String azureKey;

    @Value("${github.models.endpoint:https://models.inference.ai.azure.com}")
    private String githubModelsEndpoint;

    @Value("${github.token:}")
    private String githubToken;

    @Bean
    public OpenAIClient openAIClient() {
        if ("azure_openai".equals(provider) && StringUtils.hasText(azureKey)) {
            log.info("LLM: Azure OpenAI endpoint={}", azureEndpoint);
            return new OpenAIClientBuilder()
                    .endpoint(azureEndpoint)
                    .credential(new AzureKeyCredential(azureKey))
                    .buildClient();
        }
        log.info("LLM: GitHub Models endpoint={}", githubModelsEndpoint);
        return new OpenAIClientBuilder()
                .endpoint(githubModelsEndpoint)
                .credential(new KeyCredential(githubToken))
                .buildClient();
    }

    @Bean
    public RestClient gitHubRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }

    /**
     * Shared ObjectMapper bean — thread-safe singleton injected across all components
     * (LLMReviewTool uses it for deserialising LLM JSON responses).
     * Marked @Primary so Spring prefers this over the auto-configured default.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
