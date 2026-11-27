package com.vaultri.core.deal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.auth.entity.IdempotencyKey;
import com.vaultri.core.auth.repository.IdempotencyKeyRepository;
import com.vaultri.core.deal.dto.CreateDealCommand;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import com.vaultri.core.payment.repository.PaymentRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DealCreationStepService {

    private final DealService dealService;
    private final DealRepository dealRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    @Getter
    public static class StepAResult {
        private final boolean readyForStepB;
        private final boolean alreadyCompleted;
        private final boolean differentHashConflict;
        private final boolean inProgressNoResume;
        private final boolean recovered;
        private final Deal deal;
        private final Payment payment;
        private final Integer storedStatus;
        private final String storedBody;

        private StepAResult(boolean readyForStepB, boolean alreadyCompleted, boolean differentHashConflict,
                            boolean inProgressNoResume, boolean recovered, Deal deal, Payment payment, Integer storedStatus, String storedBody) {
            this.readyForStepB = readyForStepB;
            this.alreadyCompleted = alreadyCompleted;
            this.differentHashConflict = differentHashConflict;
            this.inProgressNoResume = inProgressNoResume;
            this.recovered = recovered;
            this.deal = deal;
            this.payment = payment;
            this.storedStatus = storedStatus;
            this.storedBody = storedBody;
        }

        public static StepAResult ready(Deal deal, Payment payment, boolean recovered) {
            return new StepAResult(true, false, false, false, recovered, deal, payment, null, null);
        }

        public static StepAResult completed(int status, String body) {
            return new StepAResult(false, true, false, false, false, null, null, status, body);
        }

        public static StepAResult differentHash() {
            return new StepAResult(false, false, true, false, false, null, null, null, null);
        }

        public static StepAResult inProgress() {
            return new StepAResult(false, false, false, true, false, null, null, null, null);
        }
    }

    /**
     * Step A (Transactional): Claim key, create draft deal, move to DEPOSIT_PENDING, insert payment, link key to deal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StepAResult executeStepA(UUID sellerId, String key, String requestHash, CreateDealRequest request) {
        UUID keyId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant initialLock = now.plusSeconds(30);

        int inserted = idempotencyKeyRepository.insertIfNotExists(keyId, sellerId, key, requestHash, now, initialLock);
        if (inserted == 1) {
            CreateDealCommand command = CreateDealCommand.builder()
                    .customerName(request.customerName())
                    .customerEmail(request.customerEmail())
                    .customerPhone(request.customerPhone())
                    .title(request.title())
                    .price(request.price())
                    .depositPercent(request.depositPercent())
                    .dueDate(request.dueDate())
                    .contractText(request.contractText())
                    .build();

            Deal draft = dealService.createDraft(sellerId, command);
            Deal pending = dealService.markDepositPending(draft.getId(), sellerId);

            Payment payment = Payment.builder()
                    .deal(pending)
                    .type(PaymentType.DEPOSIT)
                    .amount(pending.getDepositAmount())
                    .status(PaymentStatus.CREATED)
                    .razorpayLinkId(null)
                    .shortUrl(null)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            Payment savedPayment = paymentRepository.save(payment);

            IdempotencyKey keyEntity = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(sellerId, key).orElseThrow();
            keyEntity.setDeal(pending);
            idempotencyKeyRepository.save(keyEntity);

            return StepAResult.ready(pending, savedPayment, false);
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
                Payment payment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT).orElse(null);
                if (payment != null && payment.getRazorpayLinkId() == null) {
                    // Try to acquire the execution lock so only one retry thread can resume Step B
                    int acquired = idempotencyKeyRepository.tryAcquireLock(sellerId, key, now, now.plusSeconds(30));
                    if (acquired == 1) {
                        return StepAResult.ready(deal, payment, true);
                    }
                }
            }

            return StepAResult.inProgress();
        }
    }

    /**
     * Step B (Transactional): Commit gateway link id, URL, and 201 idempotency response.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DealResponse executeStepB(UUID sellerId, String key, UUID dealId, UUID paymentId, PaymentLinkResponse linkResponse) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        payment.setRazorpayLinkId(linkResponse.linkId());
        payment.setShortUrl(linkResponse.shortUrl());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        Deal deal = dealRepository.findById(dealId).orElseThrow();
        DealResponse dealResponse = DealResponse.from(deal, payment);

        try {
            IdempotencyKey keyEntity = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(sellerId, key).orElseThrow();
            keyEntity.setResponseStatus(201);
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
