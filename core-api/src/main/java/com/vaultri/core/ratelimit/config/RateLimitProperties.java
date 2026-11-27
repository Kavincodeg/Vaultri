package com.vaultri.core.ratelimit.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "vaultri.ratelimit")
public class RateLimitProperties {

    private final Environment environment;

    private boolean enabled = false;
    private int trustedProxyCount = 0;

    @ToString.Exclude
    private String internalSecret;

    private Map<String, RuleConfig> rules = new HashMap<>();

    public RateLimitProperties() {
        this.environment = null;
    }

    @Autowired
    public RateLimitProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (environment != null && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            if (trustedProxyCount > 0) {
                if (internalSecret == null || internalSecret.isBlank() || internalSecret.length() < 32) {
                    throw new IllegalStateException(
                            "Production profile with trusted-proxy-count > 0 requires RATELIMIT_INTERNAL_SECRET (vaultri.ratelimit.internal-secret) to be at least 32 characters."
                    );
                }
            }
        }
    }

    @Data
    public static class RuleConfig {
        private int limit;
        private Duration window;

        public RuleConfig() {}

        public RuleConfig(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
    }

    public RuleConfig getRule(String ruleName, int defaultLimit, Duration defaultWindow) {
        RuleConfig cfg = rules.get(ruleName);
        if (cfg == null) {
            return new RuleConfig(defaultLimit, defaultWindow);
        }
        int limit = cfg.getLimit() > 0 ? cfg.getLimit() : defaultLimit;
        Duration window = cfg.getWindow() != null ? cfg.getWindow() : defaultWindow;
        return new RuleConfig(limit, window);
    }
}
