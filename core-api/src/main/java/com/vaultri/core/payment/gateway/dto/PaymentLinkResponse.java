package com.vaultri.core.payment.gateway.dto;

public record PaymentLinkResponse(
        String linkId,
        String shortUrl,
        String status
) {
    /**
     * 2-argument constructor defaulting status to "created" only for the response of createPaymentLink itself.
     */
    public PaymentLinkResponse(String linkId, String shortUrl) {
        this(linkId, shortUrl, "created");
    }
}

