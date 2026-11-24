package com.vaultri.core.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayPropertiesValidationTest {

    @Test
    @DisplayName("Prod profile fails to start when key-id is missing or empty")
    void testProdProfileFailsWhenKeyIdMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RazorpayProperties properties = new RazorpayProperties(env);
        properties.setKeyId("");
        properties.setKeySecret("secret_xyz");
        properties.setWebhookSecret("webhook_xyz");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_KEY_ID");
    }

    @Test
    @DisplayName("Prod profile fails to start when key-secret is missing or empty")
    void testProdProfileFailsWhenKeySecretMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RazorpayProperties properties = new RazorpayProperties(env);
        properties.setKeyId("rzp_live_123");
        properties.setKeySecret("");
        properties.setWebhookSecret("webhook_xyz");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_KEY_SECRET");
    }

    @Test
    @DisplayName("Prod profile fails to start when webhook-secret is missing or empty")
    void testProdProfileFailsWhenWebhookSecretMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RazorpayProperties properties = new RazorpayProperties(env);
        properties.setKeyId("rzp_live_123");
        properties.setKeySecret("secret_xyz");
        properties.setWebhookSecret("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
    }

    @Test
    @DisplayName("Prod profile succeeds when all three properties are configured")
    void testProdProfileSucceedsWithValidProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        RazorpayProperties properties = new RazorpayProperties(env);
        properties.setKeyId("rzp_live_1234567890");
        properties.setKeySecret("prod_key_secret_value");
        properties.setWebhookSecret("prod_webhook_secret_value");

        properties.validate();
        assertThat(properties.getKeyId()).isEqualTo("rzp_live_1234567890");
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.razorpay.com");
    }

    @Test
    @DisplayName("toString must never print key-secret or webhook-secret")
    void testToStringNeverLeaksSecrets() {
        MockEnvironment env = new MockEnvironment();
        RazorpayProperties properties = new RazorpayProperties(env);
        properties.setKeyId("rzp_test_12345");
        properties.setKeySecret("super_secret_key_password");
        properties.setWebhookSecret("super_secret_webhook_signature");

        String str = properties.toString();
        assertThat(str).contains("rzp_test_12345");
        assertThat(str).doesNotContain("super_secret_key_password");
        assertThat(str).doesNotContain("super_secret_webhook_signature");
    }
}
