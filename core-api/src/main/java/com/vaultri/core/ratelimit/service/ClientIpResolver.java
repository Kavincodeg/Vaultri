package com.vaultri.core.ratelimit.service;

import com.vaultri.core.ratelimit.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final RateLimitProperties properties;

    public String resolveClientIp(HttpServletRequest request) {
        int trustedProxies = properties.getTrustedProxyCount();
        if (trustedProxies <= 0) {
            return request.getRemoteAddr();
        }

        String configuredSecret = properties.getInternalSecret();
        if (configuredSecret == null || configuredSecret.isBlank()) {
            return request.getRemoteAddr();
        }

        String headerSecret = request.getHeader("X-Internal-Secret");
        if (headerSecret == null || headerSecret.isBlank()) {
            return request.getRemoteAddr();
        }

        // Compare in constant time using MessageDigest.isEqual
        byte[] expectedBytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = headerSecret.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            return request.getRemoteAddr();
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return request.getRemoteAddr();
        }

        String[] parts = xff.split(",");
        int targetIndex = parts.length - trustedProxies;
        if (targetIndex < 0) {
            // When the header has fewer entries than the trusted count, fall back to getRemoteAddr(), not the first entry
            return request.getRemoteAddr();
        }
        return parts[targetIndex].trim();
    }
}
