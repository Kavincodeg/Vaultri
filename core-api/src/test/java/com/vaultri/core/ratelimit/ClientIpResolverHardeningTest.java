package com.vaultri.core.ratelimit;

import com.vaultri.core.ratelimit.config.RateLimitProperties;
import com.vaultri.core.ratelimit.service.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverHardeningTest {

    private static final String VALID_SECRET = "super-secret-internal-header-key-32chars!";

    @Test
    @DisplayName("trustedProxyCount = 1 with correct secret and 'forged, 1.2.3.4' resolves to 1.2.3.4")
    void testCount1WithCorrectSecret() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxyCount(1);
        properties.setInternalSecret(VALID_SECRET);

        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // Next.js server IP
        request.addHeader("X-Forwarded-For", "forged.ip.address, 1.2.3.4");
        request.addHeader("X-Internal-Secret", VALID_SECRET);

        assertThat(resolver.resolveClientIp(request)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("Wrong X-Internal-Secret ignores X-Forwarded-For and falls back to getRemoteAddr()")
    void testWrongSecretFallsBack() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxyCount(1);
        properties.setInternalSecret(VALID_SECRET);

        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Internal-Secret", "wrong-secret-key-that-does-not-match!");

        assertThat(resolver.resolveClientIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("Missing X-Internal-Secret ignores X-Forwarded-For and falls back to getRemoteAddr()")
    void testMissingSecretFallsBack() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxyCount(1);
        properties.setInternalSecret(VALID_SECRET);

        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        // No X-Internal-Secret header

        assertThat(resolver.resolveClientIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("Fewer entries than trusted count falls back to getRemoteAddr(), not the first entry")
    void testFewerEntriesThanCountFallsBack() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxyCount(3);
        properties.setInternalSecret(VALID_SECRET);

        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        // Only 2 entries when trusted count is 3
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        request.addHeader("X-Internal-Secret", VALID_SECRET);

        assertThat(resolver.resolveClientIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("trustedProxyCount = 0 ignores X-Forwarded-For even with valid secret")
    void testCount0IgnoresHeader() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxyCount(0);
        properties.setInternalSecret(VALID_SECRET);

        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Internal-Secret", VALID_SECRET);

        assertThat(resolver.resolveClientIp(request)).isEqualTo("10.0.0.1");
    }
}
