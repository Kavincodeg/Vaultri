package com.vaultri.core.mail.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailPropertiesValidationTest {

    @Test
    @DisplayName("Dev profile succeeds even when SMTP properties are not configured")
    void testDevProfileSucceedsWithoutSmtp() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        MailProperties props = new MailProperties(env);
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Prod profile fails to start when host is missing or blank")
    void testProdProfileFailsWhenHostMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        MailProperties props = new MailProperties(env);
        props.setUsername("smtp_user");
        props.setPassword("smtp_pass");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host");
    }

    @Test
    @DisplayName("Prod profile fails to start when username is missing or blank")
    void testProdProfileFailsWhenUsernameMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        MailProperties props = new MailProperties(env);
        props.setHost("smtp.example.com");
        props.setPassword("smtp_pass");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
    }

    @Test
    @DisplayName("Prod profile fails to start when password is missing or blank")
    void testProdProfileFailsWhenPasswordMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        MailProperties props = new MailProperties(env);
        props.setHost("smtp.example.com");
        props.setUsername("smtp_user");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password");
    }

    @Test
    @DisplayName("toString() never exposes the mail password")
    void testToStringNeverExposesPassword() {
        MockEnvironment env = new MockEnvironment();
        MailProperties props = new MailProperties(env);
        props.setPassword("super_secret_smtp_password");

        assertThat(props.toString()).doesNotContain("super_secret_smtp_password");
        assertThat(props.toString()).contains("***");
    }

    private static org.assertj.core.api.AbstractCharSequenceAssert<?, String> assertThat(String actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
