package com.vaultri.core.reminder.service;

import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.mail.service.MailSender;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderStatus;
import com.vaultri.core.reminder.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderDispatchService {

    private final ReminderRepository reminderRepository;
    private final PaymentRepository paymentRepository;
    private final MailSender mailSender;
    private final AuditService auditService;

    /**
     * Claims and dispatches ONE due reminder in its own transaction using SELECT ... FOR UPDATE SKIP LOCKED.
     * Transaction boundaries:
     * 1. This method opens the transaction.
     * 2. findNextDueReminderIdForUpdate executes SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1.
     * 3. Row lock is held while the deal is re-checked and the email is dispatched via SMTP.
     * 4. Reminder is marked SENT or attempts incremented and saved.
     * 5. The transaction commits, releasing the row lock.
     *
     * Returns true if a reminder was claimed and processed, false if no due reminders were available.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimAndDispatchOneReminder() {
        Optional<UUID> nextIdOpt = reminderRepository.findNextDueReminderIdForUpdate(Instant.now());
        if (nextIdOpt.isEmpty()) {
            return false;
        }

        UUID reminderId = nextIdOpt.get();
        Reminder reminder = reminderRepository.findByIdWithDealAndSeller(reminderId).orElse(null);
        if (reminder == null || reminder.getStatus() != ReminderStatus.PENDING) {
            return true;
        }

        Deal deal = reminder.getDeal();
        if (deal.getStatus() != DealStatus.DEPOSIT_PENDING) {
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminderRepository.save(reminder);
            return true;
        }

        String recipient = deal.getCustomerEmail();
        if (recipient == null || recipient.isBlank()) {
            recipient = deal.getSeller().getEmail();
        }

        Optional<Payment> depositOpt = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
        String paymentUrl = "";
        if (depositOpt.isPresent()) {
            Payment dep = depositOpt.get();
            if (dep.getStatus() == PaymentStatus.CREATED && dep.getShortUrl() != null) {
                paymentUrl = dep.getShortUrl();
            }
        }

        String subject = "Payment Reminder for " + (deal.getTitle() != null ? deal.getTitle() : "Your Deal");
        String body = String.format("Deal: %s\nAmount Due: %d paise\nDue Date: %s\nPayment Link: %s",
                deal.getTitle() != null ? deal.getTitle() : "",
                deal.getDepositAmount(),
                deal.getDueDate() != null ? deal.getDueDate().toString() : "N/A",
                paymentUrl);

        try {
            mailSender.sendEmail(recipient, subject, body);
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setSentAt(Instant.now());
            reminder.setNextAttemptAt(null);
            reminderRepository.save(reminder);
            auditService.logAction(deal, "REMINDER_SENT", "Deposit payment reminder sent successfully");
            return true;
        } catch (Exception ex) {
            // SMTP failures log only the exception class name; never recipient addresses, subjects or bodies
            log.error("SMTP delivery failure for reminder {}: {}", reminder.getId(), ex.getClass().getSimpleName());
            int attempts = reminder.getAttempts() + 1;
            reminder.setAttempts(attempts);
            if (attempts >= 3) {
                reminder.setStatus(ReminderStatus.FAILED);
                reminder.setNextAttemptAt(null);
                auditService.logAction(deal, "REMINDER_FAILED", "Reminder delivery failed after 3 attempts");
            } else if (attempts == 1) {
                // Backoff after attempt 1: 15 minutes
                reminder.setNextAttemptAt(Instant.now().plus(java.time.Duration.ofMinutes(15)));
            } else if (attempts == 2) {
                // Backoff after attempt 2: 60 minutes
                reminder.setNextAttemptAt(Instant.now().plus(java.time.Duration.ofMinutes(60)));
            }
            reminderRepository.save(reminder);
            return false;
        }
    }

    /**
     * Delivery is at-least-once (a crash after sending but before the commit can resend).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleReminder(UUID reminderId) {
        Reminder reminder = reminderRepository.findByIdWithDealAndSeller(reminderId).orElse(null);
        if (reminder == null || reminder.getStatus() != ReminderStatus.PENDING) {
            return;
        }

        Deal deal = reminder.getDeal();
        if (deal.getStatus() != DealStatus.DEPOSIT_PENDING) {
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminderRepository.save(reminder);
            return;
        }

        String recipient = deal.getCustomerEmail();
        if (recipient == null || recipient.isBlank()) {
            recipient = deal.getSeller().getEmail();
        }

        Optional<Payment> depositOpt = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
        String paymentUrl = "";
        if (depositOpt.isPresent()) {
            Payment dep = depositOpt.get();
            if (dep.getStatus() == PaymentStatus.CREATED && dep.getShortUrl() != null) {
                paymentUrl = dep.getShortUrl();
            }
        }

        String subject = "Payment Reminder for " + (deal.getTitle() != null ? deal.getTitle() : "Your Deal");
        String body = String.format("Deal: %s\nAmount Due: %d paise\nDue Date: %s\nPayment Link: %s",
                deal.getTitle() != null ? deal.getTitle() : "",
                deal.getDepositAmount(),
                deal.getDueDate() != null ? deal.getDueDate().toString() : "N/A",
                paymentUrl);

        try {
            mailSender.sendEmail(recipient, subject, body);
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setSentAt(Instant.now());
            reminderRepository.save(reminder);
            auditService.logAction(deal, "REMINDER_SENT", "Deposit payment reminder sent successfully");
        } catch (Exception ex) {
            // SMTP failures log only the exception class name; never recipient addresses, subjects or bodies
            log.error("SMTP delivery failure for reminder {}: {}", reminder.getId(), ex.getClass().getSimpleName());
            int attempts = reminder.getAttempts() + 1;
            reminder.setAttempts(attempts);
            if (attempts >= 3) {
                reminder.setStatus(ReminderStatus.FAILED);
                auditService.logAction(deal, "REMINDER_FAILED", "Reminder delivery failed after 3 attempts");
            }
            reminderRepository.save(reminder);
        }
    }
}
