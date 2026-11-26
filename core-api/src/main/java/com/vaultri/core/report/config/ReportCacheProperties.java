package com.vaultri.core.report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "vaultri.cache")
public class ReportCacheProperties {

    private boolean enabled = false;
    private Duration ttl = Duration.ofSeconds(60);
}
