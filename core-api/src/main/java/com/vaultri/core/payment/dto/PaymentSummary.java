package com.vaultri.core.payment.dto;

import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;

import java.util.UUID;

public record PaymentSummary(
        UUID id,
        PaymentStatus status,
        Long amount,
        String razorpayPaymentLinkId,
        String paymentUrl
) {
    public static PaymentSummary from(Payment payment) {
        if (payment == null) return null;
        return new PaymentSummary(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getRazorpayLinkId(),
                payment.getShortUrl()
        );
    }
}
