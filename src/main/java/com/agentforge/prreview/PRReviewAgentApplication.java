package com.agentforge.prreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * PR Review Agent for Spring Boot
 *
 * Automated PR review pipeline combining OWASP static analysis with
 * LLM holistic review. Posts structured inline comments to GitHub PRs.
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties
public class PRReviewAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PRReviewAgentApplication.class, args);
    }
}
