package com.vaultri.core.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

@Component
@ConfigurationProperties(prefix = "vaultri.jwt")
@Getter
@Setter
public class JwtProperties {

    public static final String DEV_DEFAULT_SECRET = "dev-only-jwt-secret-do-not-use-in-production-min-32-bytes!";

    private final Environment environment;

    /**
     * HS256 secret key (minimum 32 bytes / 256 bits).
     */
    private String secret = DEV_DEFAULT_SECRET;

    /**
     * Issuer claim ("iss").
     */
    private String issuer = "vaultri-core-api";

    /**
     * Access token validity duration (default: 15 minutes).
     */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /**
     * Refresh token validity duration (default: 7 days).
     */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    public JwtProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd) {
            if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "Production profile requires JWT_SECRET to be configured with at least 32 bytes."
                );
            }
            if (DEV_DEFAULT_SECRET.equals(secret)) {
                throw new IllegalStateException(
                        "Default development secret cannot be used in production profile."
                );
            }
        } else {
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "JWT secret must be at least 32 bytes for HS256 algorithm."
                );
            }
        }
    }
}
