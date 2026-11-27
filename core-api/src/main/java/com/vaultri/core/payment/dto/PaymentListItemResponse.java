package com.vaultri.core.payment.dto;

import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;

import java.time.Instant;
import java.util.UUID;

public record PaymentListItemResponse(
        UUID id,
        UUID dealId,
        String dealTitle,
        PaymentType type,
        PaymentStatus status,
        Long amount,
        String paymentUrl,
        Instant createdAt
) {
    public static PaymentListItemResponse from(Payment payment) {
        return new PaymentListItemResponse(
                payment.getId(),
                payment.getDeal().getId(),
                payment.getDeal().getTitle(),
                payment.getType(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getShortUrl(),
                payment.getCreatedAt()
        );
    }
}
