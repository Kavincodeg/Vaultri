package com.vaultri.core.deal.dto;

import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.payment.dto.PaymentSummary;
import com.vaultri.core.payment.entity.Payment;

import java.time.Instant;
import java.util.UUID;

public record DealResponse(
        UUID id,
        UUID sellerId,
        String customerName,
        String customerEmail,
        String customerPhone,
        String title,
        Long price,
        Integer depositPercent,
        Long depositAmount,
        Instant dueDate,
        DealStatus status,
        String contractText,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        PaymentSummary depositPayment,
        PaymentSummary cancellationFeePayment
) {
    public static DealResponse from(Deal deal) {
        return from(deal, null, null);
    }

    public static DealResponse from(Deal deal, Payment depositPayment) {
        return from(deal, depositPayment, null);
    }

    public static DealResponse from(Deal deal, Payment depositPayment, Payment cancellationFeePayment) {
        return new DealResponse(
                deal.getId(),
                deal.getSeller().getId(),
                deal.getCustomerName(),
                deal.getCustomerEmail(),
                deal.getCustomerPhone(),
                deal.getTitle(),
                deal.getPrice(),
                deal.getDepositPercent(),
                deal.getDepositAmount(),
                deal.getDueDate(),
                deal.getStatus(),
                deal.getContractText(),
                deal.getVersion(),
                deal.getCreatedAt(),
                deal.getUpdatedAt(),
                PaymentSummary.from(depositPayment),
                PaymentSummary.from(cancellationFeePayment)
        );
    }
}
