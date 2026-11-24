package com.vaultri.core.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.state.DealStateMachine;
import com.vaultri.core.ledger.service.LedgerService;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.webhook.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternalSettlementService {

    private final WebhookRepository webhookRepository;
    private final PaymentRepository paymentRepository;
    private final DealRepository dealRepository;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Atomically claims and processes the webhook event within a single database transaction.
     * Returns true if processed successfully (or recognized duplicate), or throws to roll back.
     */
    @Transactional
    public void processWebhookEvent(String eventId, String rawPayload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            log.error("Failed to parse webhook JSON for eventId: {}", eventId);
            throw new IllegalArgumentException("Malformed JSON payload");
        }

        String eventType = root.path("event").asText("");
        Instant now = Instant.now();

        // Step a: Atomic insert into webhook_events. If already exists (affected == 0), it's a duplicate redelivery.
        int inserted = webhookRepository.insertIfNotExists(UUID.randomUUID(), eventId, eventType, rawPayload, now);
        if (inserted == 0) {
            log.info("Duplicate webhook event ignored: {}", eventId);
            return;
        }

        // Step b: Extract payment_link entity details
        JsonNode paymentLinkEntity = root.path("payload").path("payment_link").path("entity");
        String linkId = paymentLinkEntity.path("id").asText(null);

        if (linkId == null || linkId.isBlank()) {
            log.warn("Webhook event {} does not contain payment_link.entity.id, skipping settlement", eventId);
            webhookRepository.markProcessed(eventId, now);
            return;
        }

        Optional<Payment> paymentOpt = paymentRepository.findByRazorpayLinkIdWithDealAndSeller(linkId);
        if (paymentOpt.isEmpty()) {
            log.warn("Webhook event {} referenced unknown payment_link.id: {}", eventId, linkId);
            webhookRepository.markProcessed(eventId, now);
            return;
        }

        Payment payment = paymentOpt.get();
        Deal deal = payment.getDeal();

        // Step c: Process event according to event_type
        switch (eventType) {
            case "payment_link.paid" -> handlePaymentLinkPaid(eventId, root, paymentLinkEntity, payment, deal, now);
            case "payment_link.expired" -> handlePaymentLinkExpired(payment, deal, now);
            case "payment_link.cancelled" -> handlePaymentLinkCancelled(payment, deal, now);
            default -> {
                log.info("Ignoring unhandled webhook event type: {} for event {}", eventType, eventId);
            }
        }

        // Step f: Mark webhook_event as processed
        webhookRepository.markProcessed(eventId, now);
    }

    private void handlePaymentLinkPaid(String eventId, JsonNode root, JsonNode paymentLinkEntity, Payment payment, Deal deal, Instant now) {
        long amountPaid = paymentLinkEntity.path("amount_paid").asLong(paymentLinkEntity.path("amount").asLong(0L));
        if (amountPaid == 0L) {
            amountPaid = root.path("payload").path("payment").path("entity").path("amount").asLong(0L);
        }

        // Amount verification
        if (amountPaid != payment.getAmount()) {
            log.error("Payment amount mismatch for event {}: expected {}, got {}", eventId, payment.getAmount(), amountPaid);
            auditService.logAction(deal, "PAYMENT_AMOUNT_MISMATCH",
                    String.format("Payment amount mismatch: expected %d, got %d", payment.getAmount(), amountPaid));
            return;
        }

        // Extract razorpay_payment_id if present
        String razorpayPaymentId = root.path("payload").path("payment").path("entity").path("id").asText(null);
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
            razorpayPaymentId = paymentLinkEntity.path("payment_id").asText(null);
        }
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
            razorpayPaymentId = "pay_simulated_" + eventId;
        }

        // Conditional atomic update
        int updatedRows = paymentRepository.markPaidIfCreated(payment.getId(), razorpayPaymentId, now);
        if (updatedRows != 1) {
            log.warn("Payment {} was not in CREATED status during paid event {}", payment.getId(), eventId);
            auditService.logAction(deal, "UNEXPECTED_PAID_EVENT",
                    "Received payment_link.paid event but payment was not in CREATED status");
            return;
        }

        // Record ledger entry
        ledgerService.recordEntry(deal, deal.getSeller(), payment, payment.getType(), payment.getAmount());

        // Lifecycle transition logic
        if (payment.getType() == PaymentType.DEPOSIT) {
            if (deal.getStatus() == DealStatus.CANCELLED) {
                auditService.logAction(deal, "PAYMENT_RECEIVED_AFTER_CANCELLATION",
                        "Deposit payment received after deal was already cancelled");
            } else {
                DealStateMachine.transition(deal.getStatus(), DealStatus.DEPOSIT_PAID);
                deal.setStatus(DealStatus.DEPOSIT_PAID);
                deal.setUpdatedAt(now);
                dealRepository.save(deal);
                auditService.logAction(deal, "DEPOSIT_PAID", "Deposit payment confirmed via Razorpay webhook");
            }
        }
    }

    private void handlePaymentLinkExpired(Payment payment, Deal deal, Instant now) {
        int updated = paymentRepository.updateStatusIfCreated(payment.getId(), PaymentStatus.EXPIRED, now);
        if (updated == 1) {
            auditService.logAction(deal, "PAYMENT_LINK_EXPIRED", "Payment link expired");
        }
    }

    private void handlePaymentLinkCancelled(Payment payment, Deal deal, Instant now) {
        int updated = paymentRepository.updateStatusIfCreated(payment.getId(), PaymentStatus.CANCELLED, now);
        if (updated == 1) {
            auditService.logAction(deal, "PAYMENT_LINK_CANCELLED", "Payment link cancelled");
        }
    }
}
