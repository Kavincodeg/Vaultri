package com.vaultri.core.ratelimit;

import com.vaultri.core.ratelimit.config.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesValidationTest {

    @Test
    @DisplayName("Prod profile fails to start if trustedProxyCount > 0 and internalSecret is blank")
    void testProdFailsWhenSecretBlank() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RateLimitProperties props = new RateLimitProperties(env);
        props.setTrustedProxyCount(1);
        props.setInternalSecret("");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RATELIMIT_INTERNAL_SECRET");
    }

    @Test
    @DisplayName("Prod profile fails to start if trustedProxyCount > 0 and internalSecret is shorter than 32 characters")
    void testProdFailsWhenSecretTooShort() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RateLimitProperties props = new RateLimitProperties(env);
        props.setTrustedProxyCount(1);
        props.setInternalSecret("short-secret-less-32-chars");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    @DisplayName("Prod profile succeeds when trustedProxyCount > 0 and internalSecret has 32+ characters")
    void testProdSucceedsWithValidSecret() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RateLimitProperties props = new RateLimitProperties(env);
        props.setTrustedProxyCount(1);
        props.setInternalSecret("valid-internal-secret-that-is-at-least-32-chars-long!");

        props.validate();
        assertThat(props.getInternalSecret()).isEqualTo("valid-internal-secret-that-is-at-least-32-chars-long!");
    }

    @Test
    @DisplayName("internalSecret must never appear in RateLimitProperties toString()")
    void testSecretNotInToString() {
        RateLimitProperties props = new RateLimitProperties();
        props.setInternalSecret("top-secret-super-confidential-32char!");

        assertThat(props.toString()).doesNotContain("top-secret-super-confidential-32char!");
    }
}
