package com.vaultri.core.deal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.auth.entity.IdempotencyKey;
import com.vaultri.core.auth.repository.IdempotencyKeyRepository;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.DealNotFoundException;
import com.vaultri.core.deal.policy.CancellationFeePolicy;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.state.DealStateMachine;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.reminder.repository.ReminderRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DealCancellationStepService {

    private final DealRepository dealRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ReminderRepository reminderRepository;
    private final CancellationFeePolicy cancellationFeePolicy;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Getter
    public static class StepAResult {
        private final boolean readyForStepB;
        private final boolean alreadyCompleted;
        private final boolean differentHashConflict;
        private final boolean inProgressNoResume;
        private final Deal deal;
        private final Payment feePayment;
        private final Payment depositPaymentToCancel;
        private final Integer storedStatus;
        private final String storedBody;

        private StepAResult(boolean readyForStepB, boolean alreadyCompleted, boolean differentHashConflict,
                            boolean inProgressNoResume, Deal deal, Payment feePayment, Payment depositPaymentToCancel,
                            Integer storedStatus, String storedBody) {
            this.readyForStepB = readyForStepB;
            this.alreadyCompleted = alreadyCompleted;
            this.differentHashConflict = differentHashConflict;
            this.inProgressNoResume = inProgressNoResume;
            this.deal = deal;
            this.feePayment = feePayment;
            this.depositPaymentToCancel = depositPaymentToCancel;
            this.storedStatus = storedStatus;
            this.storedBody = storedBody;
        }

        public static StepAResult ready(Deal deal, Payment feePayment, Payment depositPaymentToCancel) {
            return new StepAResult(true, false, false, false, deal, feePayment, depositPaymentToCancel, null, null);
        }

        public static StepAResult completed(int status, String body) {
            return new StepAResult(false, true, false, false, null, null, null, status, body);
        }

        public static StepAResult differentHash() {
            return new StepAResult(false, false, true, false, null, null, null, null, null);
        }

        public static StepAResult inProgress() {
            return new StepAResult(false, false, false, true, null, null, null, null, null);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StepAResult executeStepA(UUID sellerId, UUID dealId, String key, String requestHash, CancelDealRequest request) {
        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant initialLock = now.plusSeconds(30);

        int inserted = idempotencyKeyRepository.insertIfNotExists(keyId, sellerId, key, requestHash, now, initialLock);
        if (inserted == 1) {
            Deal deal = dealRepository.findByIdAndSellerId(dealId, sellerId)
                    .orElseThrow(() -> new DealNotFoundException(dealId));

            cancellationFeePolicy.validateFee(deal, request.requestedFeePaise());

            DealStateMachine.transition(deal.getStatus(), DealStatus.CANCELLED);
            deal.setStatus(DealStatus.CANCELLED);
            deal.setUpdatedAt(now);
            dealRepository.save(deal);

            String reason = request.reason() != null ? request.reason() : "";
            auditService.logAction(deal, "CANCELLED",
                    String.format("Fee: %d paise; Reason: %s", request.requestedFeePaise(), reason));

            reminderRepository.cancelPendingByDealId(deal.getId());

            Payment feePayment = null;
            if (request.requestedFeePaise() > 0) {
                Payment fee = Payment.builder()
                        .deal(deal)
                        .type(PaymentType.CANCELLATION_FEE)
                        .amount(request.requestedFeePaise())
                        .status(PaymentStatus.CREATED)
                        .razorpayLinkId(null)
                        .shortUrl(null)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                feePayment = paymentRepository.save(fee);
            }

            Payment depositPaymentToCancel = null;
            Optional<Payment> depositOpt = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
            if (depositOpt.isPresent()) {
                Payment dep = depositOpt.get();
                if (dep.getStatus() == PaymentStatus.CREATED && dep.getRazorpayLinkId() != null) {
                    dep.setCancelRequestedAt(now);
                    dep.setUpdatedAt(now);
                    depositPaymentToCancel = paymentRepository.save(dep);
                }
            }

            IdempotencyKey keyEntity = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(sellerId, key).orElseThrow();
            keyEntity.setDeal(deal);
            idempotencyKeyRepository.save(keyEntity);

            return StepAResult.ready(deal, feePayment, depositPaymentToCancel);
        } else {
            IdempotencyKey existingKey = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(sellerId, key).orElseThrow();
            if (!Objects.equals(existingKey.getRequestHash(), requestHash)) {
                return StepAResult.differentHash();
            }
            if (existingKey.getResponseStatus() != null) {
                return StepAResult.completed(existingKey.getResponseStatus(), existingKey.getResponseBody());
            }

            Deal deal = existingKey.getDeal();
            if (deal != null) {
                Payment feePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE).orElse(null);
                Payment depositPaymentToCancel = null;
                Optional<Payment> depositOpt = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
                if (depositOpt.isPresent()) {
                    Payment dep = depositOpt.get();
                    if (dep.getStatus() == PaymentStatus.CREATED && dep.getRazorpayLinkId() != null && dep.getCancelRequestedAt() != null) {
                        depositPaymentToCancel = dep;
                    }
                }

                if (request.requestedFeePaise() > 0) {
                    if (feePayment != null && feePayment.getRazorpayLinkId() == null) {
                        int acquired = idempotencyKeyRepository.tryAcquireLock(sellerId, key, now, now.plusSeconds(30));
                        if (acquired == 1) {
                            return StepAResult.ready(deal, feePayment, depositPaymentToCancel);
                        }
                    }
                } else {
                    int acquired = idempotencyKeyRepository.tryAcquireLock(sellerId, key, now, now.plusSeconds(30));
                    if (acquired == 1) {
                        return StepAResult.ready(deal, null, depositPaymentToCancel);
                    }
                }
            }

            return StepAResult.inProgress();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDepositCancelled(UUID paymentId) {
        paymentRepository.markCancelledAndClearCancelRequestedIfCreated(paymentId, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDepositCancelPending(UUID dealId) {
        Deal deal = dealRepository.findById(dealId).orElse(null);
        if (deal != null) {
            auditService.logAction(deal, "DEPOSIT_LINK_CANCEL_PENDING", "Gateway failed to cancel deposit payment link; flagged for retry");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DealResponse executeStepB(UUID sellerId, String key, UUID dealId, UUID feePaymentId, PaymentLinkResponse linkResponse) {
        Deal deal = dealRepository.findById(dealId).orElseThrow();
        Payment depositPayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT).orElse(null);

        Payment feePayment = null;
        if (feePaymentId != null && linkResponse != null) {
            feePayment = paymentRepository.findById(feePaymentId).orElseThrow();
            feePayment.setRazorpayLinkId(linkResponse.linkId());
            feePayment.setShortUrl(linkResponse.shortUrl());
            feePayment.setUpdatedAt(Instant.now());
            feePayment = paymentRepository.save(feePayment);
        }

        DealResponse dealResponse = DealResponse.from(deal, depositPayment, feePayment);

        try {
            IdempotencyKey keyEntity = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(sellerId, key).orElseThrow();
            keyEntity.setResponseStatus(200);
            keyEntity.setResponseBody(objectMapper.writeValueAsString(dealResponse));
            keyEntity.setLockedUntil(null);
            idempotencyKeyRepository.save(keyEntity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize idempotency response", e);
        }

        return dealResponse;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLockOnFailure(UUID sellerId, String key) {
        idempotencyKeyRepository.releaseLock(sellerId, key);
    }
}
