package com.vaultri.core.payment.gateway.dto;

import com.vaultri.core.payment.entity.PaymentType;

import java.util.UUID;

public record CreateLinkRequest(
        UUID paymentId,
        long amountPaise,
        String description,
        String customerName,
        String customerEmail,
        String customerPhone,
        UUID dealId,
        PaymentType type
) {}
