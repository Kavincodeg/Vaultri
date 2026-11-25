package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.config.RazorpayProperties;
import com.vaultri.core.payment.exception.GatewayUnavailableException;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

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

    @Override
    public PaymentLinkResponse createPaymentLink(CreateLinkRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", request.amountPaise());
        body.put("currency", "INR");
        body.put("description", request.description());
        body.put("reference_id", request.paymentId().toString());
        body.put("reminder_enable", false);

        Map<String, Object> customer = new HashMap<>();
        if (request.customerName() != null) customer.put("name", request.customerName());
        if (request.customerEmail() != null) customer.put("email", request.customerEmail());
        if (request.customerPhone() != null) customer.put("contact", request.customerPhone());
        body.put("customer", customer);

        Map<String, String> notes = new HashMap<>();
        notes.put("dealId", request.dealId().toString());
        notes.put("type", request.type().name());
        body.put("notes", notes);

        String credentials = properties.getKeyId() + ":" + properties.getKeySecret();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1/payment_links")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
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
    public void cancelPaymentLink(String linkId) {
        if (linkId == null || linkId.isBlank()) {
            return;
        }

        String credentials = properties.getKeyId() + ":" + properties.getKeySecret();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        try {
            restClient.post()
                    .uri("/v1/payment_links/{id}/cancel", linkId)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest ex) {
            // If 400 means already cancelled, expired or paid, treat as benign ALREADY_TERMINAL
            String responseBody = ex.getResponseBodyAsString().toLowerCase();
            if (responseBody.contains("cancelled") || responseBody.contains("expired") || responseBody.contains("paid") || responseBody.contains("terminal")) {
                return; // Benign ALREADY_TERMINAL
            }
            throw new GatewayUnavailableException("Payment gateway cancellation failed with 400", ex);
        } catch (Exception e) {
            // Map I/O errors, timeouts, 5xx to GatewayUnavailableException without leaking secrets
            throw new GatewayUnavailableException("Payment gateway communication failed", e);
        }
    }
}
