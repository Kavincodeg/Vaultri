package com.vaultri.core.payment.gateway.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateLinkRequest(
        UUID paymentId,
        long amountPaise,
        String title,
        UUID dealId,
        Instant dueDate
) {}
