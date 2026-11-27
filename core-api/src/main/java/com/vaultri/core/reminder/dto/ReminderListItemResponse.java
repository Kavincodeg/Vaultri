package com.vaultri.core.reminder.dto;

import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderKind;
import com.vaultri.core.reminder.entity.ReminderStatus;

import java.time.Instant;
import java.util.UUID;

public record ReminderListItemResponse(
        UUID id,
        UUID dealId,
        String dealTitle,
        ReminderKind kind,
        ReminderStatus status,
        Instant dueAt,
        Instant sentAt,
        Integer attempts
) {
    public static ReminderListItemResponse from(Reminder reminder) {
        return new ReminderListItemResponse(
                reminder.getId(),
                reminder.getDeal().getId(),
                reminder.getDeal().getTitle(),
                reminder.getKind(),
                reminder.getStatus(),
                reminder.getDueAt(),
                reminder.getSentAt(),
                reminder.getAttempts()
        );
    }
}
