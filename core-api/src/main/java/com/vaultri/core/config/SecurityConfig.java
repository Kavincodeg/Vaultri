package com.vaultri.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // TODO: /webhooks/razorpay must be public but signature-verified via X-Razorpay-Signature HMAC-SHA256, with CSRF disabled for it only.
        http
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(
                    "/api/v1/webhooks/**",
                    "/webhooks/**"
                )
            )
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator Health and Info
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                // Dedicated API Health Endpoint
                .requestMatchers("/api/v1/health", "/api/v1/health/**").permitAll()
                // Swagger / OpenAPI documentation
                .requestMatchers(
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                // Webhook endpoints (signature verified at application/filter layer)
                .requestMatchers(
                    "/api/v1/webhooks/**",
                    "/webhooks/**"
                ).permitAll()
                // All other business endpoints require authentication
                .anyRequest().authenticated()
            );

        return http.build();
    }
}
