package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.config.RazorpayProperties;
import com.vaultri.core.payment.exception.GatewayUnavailableException;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RazorpayGateway implements PaymentGateway {

    private final RazorpayProperties properties;
    private final RestClient restClient;

    @Autowired
    public RazorpayGateway(RazorpayProperties properties) {
        this(properties, RestClient.builder());
    }

    public RazorpayGateway(RazorpayProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;

        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    private String getBasicAuth() {
        String credentials = properties.getKeyId() + ":" + properties.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PaymentLinkResponse createPaymentLink(CreateLinkRequest request) {
        Instant now = Instant.now();
        long expireBy;
        if (request.dueDate() != null && request.dueDate().isAfter(now.plus(20, ChronoUnit.MINUTES))) {
            expireBy = request.dueDate().getEpochSecond();
        } else {
            expireBy = now.plus(properties.getLinkExpiryDays(), ChronoUnit.DAYS).getEpochSecond();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("amount", request.amountPaise());
        body.put("currency", "INR");
        body.put("description", request.title());
        body.put("reference_id", request.paymentId().toString());
        body.put("reminder_enable", false);
        body.put("expire_by", expireBy);

        Map<String, Boolean> notify = new HashMap<>();
        notify.put("sms", false);
        notify.put("email", false);
        body.put("notify", notify);

        Map<String, String> notes = new HashMap<>();
        notes.put("dealId", request.dealId().toString());
        body.put("notes", notes);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1/payment_links")
                    .header(HttpHeaders.AUTHORIZATION, getBasicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("id")) {
                throw new GatewayUnavailableException("Payment gateway response was empty or missing link id");
            }

            String linkId = (String) response.get("id");
            String shortUrl = (String) response.get("short_url");
            return new PaymentLinkResponse(linkId, shortUrl);
        } catch (GatewayUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // Never echo secret, authorization header, or customer contact data
            throw new GatewayUnavailableException("Payment gateway communication failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PaymentLinkResponse> findPaymentLinkByReferenceId(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<?, ?> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/payment_links")
                            .queryParam("reference_id", referenceId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, getBasicAuth())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return Optional.empty();
            }

            // Razorpay collection response contains "payment_links" or "items"
            List<?> items = null;
            if (response.containsKey("payment_links") && response.get("payment_links") instanceof List<?> list) {
                items = list;
            } else if (response.containsKey("items") && response.get("items") instanceof List<?> list) {
                items = list;
            }

            if (items == null || items.isEmpty()) {
                return Optional.empty();
            }

            for (Object obj : items) {
                if (obj instanceof Map<?, ?> map) {
                    Object refIdObj = map.get("reference_id");
                    if (refIdObj != null && referenceId.equals(refIdObj.toString())) {
                        String linkId = (String) map.get("id");
                        String shortUrl = (String) map.get("short_url");
                        String status = (String) map.get("status");
                        if (linkId != null) {
                            return Optional.of(new PaymentLinkResponse(linkId, shortUrl, (status != null && !status.isBlank()) ? status : "unknown"));
                        }
                    }
                }
            }

            return Optional.empty();
        } catch (GatewayUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayUnavailableException("Payment gateway communication failed", e);
        }
    }

    @Override
    public void cancelPaymentLink(String linkId) {
        if (linkId == null || linkId.isBlank()) {
            return;
        }

        try {
            restClient.post()
                    .uri("/v1/payment_links/{id}/cancel", linkId)
                    .header(HttpHeaders.AUTHORIZATION, getBasicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Never echo secret or authorization header
            throw new GatewayUnavailableException("Payment gateway cancel communication failed", e);
        }
    }
}
