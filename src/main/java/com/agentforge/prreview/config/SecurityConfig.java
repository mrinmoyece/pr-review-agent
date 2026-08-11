package com.agentforge.prreview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Keeps the HTTP surface default-deny. Webhook authentication is performed with
 * GitHub's HMAC signature and the optional manual trigger has its own secret.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhook/github", "/webhook/trigger"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/webhook/github", "/webhook/trigger", "/error",
                                "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny()))
                .build();
    }
}
