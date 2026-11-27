package com.vaultri.core.audit.dto;

import com.vaultri.core.audit.entity.AuditLog;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID dealId,
        String action,
        String detail,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getDeal().getId(),
                log.getAction(),
                sanitizeDetail(log.getAction()),
                log.getCreatedAt()
        );
    }

    public static String sanitizeDetail(String action) {
        if (action == null) {
            return "Action recorded";
        }
        return switch (action.toUpperCase()) {
            case "DEAL_CREATED" -> "Draft deal created";
            case "DEPOSIT_PENDING" -> "Deposit payment link generated";
            case "DEPOSIT_PAID" -> "Deposit payment confirmed";
            case "IN_PROGRESS" -> "Work on deal started";
            case "COMPLETED" -> "Deal marked as completed";
            case "CANCELLED" -> "Deal cancelled";
            case "DEPOSIT_LINK_CANCEL_PENDING" -> "Deposit payment link cancellation pending retry";
            case "DEPOSIT_LINK_CANCEL_GAVE_UP" -> "Deposit payment link cancellation attempts exceeded";
            case "REMINDER_SENT" -> "Deposit payment reminder sent";
            case "REMINDER_FAILED" -> "Deposit payment reminder delivery failed";
            case "PAYMENT_AMOUNT_MISMATCH" -> "Payment amount mismatch detected";
            case "UNEXPECTED_PAID_EVENT" -> "Unexpected payment paid event received";
            case "PAYMENT_RECEIVED_AFTER_CANCELLATION" -> "Payment received after deal was cancelled";
            case "PAYMENT_LINK_EXPIRED" -> "Payment link expired";
            case "PAYMENT_LINK_CANCELLED" -> "Payment link cancelled";
            default -> "Deal action recorded";
        };
    }
}
