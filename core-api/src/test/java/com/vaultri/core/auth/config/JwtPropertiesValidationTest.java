package com.vaultri.core.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesValidationTest {

    @Test
    @DisplayName("Prod profile fails to start when JWT secret is missing or empty")
    void testProdProfileFailsWhenSecretMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtProperties properties = new JwtProperties(env);
        properties.setSecret("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production profile requires JWT_SECRET");
    }

    @Test
    @DisplayName("Prod profile fails to start when JWT secret is less than 32 bytes")
    void testProdProfileFailsWhenSecretTooShort() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtProperties properties = new JwtProperties(env);
        properties.setSecret("short-secret-less-32-bytes");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("Prod profile fails to start if dev default secret is used")
    void testProdProfileFailsWithDevDefault() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtProperties properties = new JwtProperties(env);
        properties.setSecret(JwtProperties.DEV_DEFAULT_SECRET);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default development secret cannot be used in production profile");
    }

    @Test
    @DisplayName("Prod profile starts successfully when a valid 32+ byte secret is provided")
    void testProdProfileSucceedsWithValidSecret() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtProperties properties = new JwtProperties(env);
        properties.setSecret("prod-super-secret-key-that-is-at-least-32-bytes-long!");

        properties.validate();
        assertThat(properties.getSecret()).isEqualTo("prod-super-secret-key-that-is-at-least-32-bytes-long!");
    }

    @Test
    @DisplayName("ApplicationContextRunner verifies context fails to start in prod without JWT secret")
    void testContextRunnerFailsInProdWithoutSecret() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=prod")
                .withUserConfiguration(JwtProperties.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getCause().getMessage())
                            .containsIgnoringCase("production profile");
                });
    }
}
