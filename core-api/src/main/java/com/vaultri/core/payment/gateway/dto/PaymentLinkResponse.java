package com.vaultri.core.payment.gateway.dto;

public record PaymentLinkResponse(
        String linkId,
        String shortUrl
) {}
