package com.vaultri.core.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.payment.config.RazorpayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final int MAX_PAYLOAD_BYTES = 256 * 1024; // 256 KB
    private static final int MAX_RETRIES = 3;

    private final RazorpayProperties razorpayProperties;
    private final InternalSettlementService internalSettlementService;
    private final ObjectMapper objectMapper;

    /**
     * Verifies HMAC-SHA256 signature using constant-time comparison.
     * Fails closed if webhook secret is null or blank.
     */
    public boolean verifySignature(byte[] bodyBytes, String signature) {
        String secret = razorpayProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook verification failed: webhook secret is not configured or blank");
            return false;
        }

        if (signature == null || signature.isBlank() || bodyBytes == null || bodyBytes.length == 0) {
            return false;
        }

        if (bodyBytes.length > MAX_PAYLOAD_BYTES) {
            log.warn("Webhook verification failed: payload exceeds 256 KB");
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hashBytes = mac.doFinal(bodyBytes);
            String computedHex = HexFormat.of().formatHex(hashBytes);

            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Error computing webhook HMAC signature: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Processes inbound webhook with bounded retry wrapper outside transaction for optimistic locking conflicts.
     */
    public void processWebhook(String eventId, byte[] bodyBytes) {
        if (bodyBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload size exceeds maximum allowed size of 256KB");
        }

        String rawPayload = new String(bodyBytes, StandardCharsets.UTF_8);

        // Validate JSON before attempting transaction or DB insert
        try {
            objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON payload");
        }

        int attempts = 0;
        while (true) {
            try {
                attempts++;
                internalSettlementService.processWebhookEvent(eventId, rawPayload);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempts >= MAX_RETRIES) {
                    log.error("Exhausted retries ({}) processing webhook event {} due to optimistic locking", MAX_RETRIES, eventId);
                    throw e;
                }
                log.warn("Optimistic locking conflict on webhook event {}, attempt {} of {}. Retrying...", eventId, attempts, MAX_RETRIES);
                try {
                    Thread.sleep(50L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Webhook retry interrupted", ie);
                }
            }
        }
    }
}
