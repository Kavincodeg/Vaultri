package com.vaultri.core.ratelimit.service;

import com.vaultri.core.ratelimit.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final RateLimitProperties properties;

    public String resolveClientIp(HttpServletRequest request) {
        int trustedProxies = properties.getTrustedProxyCount();
        if (trustedProxies <= 0) {
            return request.getRemoteAddr();
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return request.getRemoteAddr();
        }

        String[] parts = xff.split(",");
        int targetIndex = parts.length - trustedProxies;
        if (targetIndex < 0) {
            return parts[0].trim();
        }
        return parts[targetIndex].trim();
    }
}
