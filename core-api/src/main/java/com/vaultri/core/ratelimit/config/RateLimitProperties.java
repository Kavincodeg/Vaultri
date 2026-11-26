package com.vaultri.core.ratelimit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "vaultri.ratelimit")
public class RateLimitProperties {

    private boolean enabled = false;
    private int trustedProxyCount = 0;
    private Map<String, RuleConfig> rules = new HashMap<>();

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
