package com.vaultri.core.payment.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@ConfigurationProperties(prefix = "vaultri.razorpay")
@Getter
@Setter
public class RazorpayProperties {

    private final Environment environment;

    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String baseUrl = "https://api.razorpay.com";

    public RazorpayProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd) {
            if (keyId == null || keyId.isBlank()) {
                throw new IllegalStateException("Production profile requires RAZORPAY_KEY_ID to be configured.");
            }
            if (keySecret == null || keySecret.isBlank()) {
                throw new IllegalStateException("Production profile requires RAZORPAY_KEY_SECRET to be configured.");
            }
            if (webhookSecret == null || webhookSecret.isBlank()) {
                throw new IllegalStateException("Production profile requires RAZORPAY_WEBHOOK_SECRET to be configured.");
            }
        }
    }

    @Override
    public String toString() {
        return "RazorpayProperties(keyId=" + keyId +
                ", keySecret=***" +
                ", webhookSecret=***" +
                ", baseUrl=" + baseUrl + ")";
    }
}
