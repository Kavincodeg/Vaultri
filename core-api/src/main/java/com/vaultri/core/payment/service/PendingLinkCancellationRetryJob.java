package com.vaultri.core.payment.service;

import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.gateway.PaymentGateway;
import com.vaultri.core.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PendingLinkCancellationRetryJob {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;

    @Scheduled(cron = "${vaultri.cancellation.retry-cron:0 */5 * * * *}")
    @SchedulerLock(name = "pendingLinkCancellationRetry", lockAtMostFor = "4m")
    public void retryPendingLinkCancellations() {
        List<Payment> pendingList = paymentRepository.findPendingCancellations();
        for (Payment payment : pendingList) {
            processPaymentCancellation(payment.getId());
        }
    }

    @Transactional
    public void processPaymentCancellation(java.util.UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            return;
        }

        // Webhook might have transitioned it to PAID or CANCELLED in the meantime
        if (payment.getStatus() != PaymentStatus.CREATED || payment.getCancelRequestedAt() == null) {
            return;
        }

        if (payment.getCancelAttempts() >= 10) {
            payment.setCancelRequestedAt(null);
            payment.setUpdatedAt(Instant.now());
            paymentRepository.save(payment);
            auditService.logAction(payment.getDeal(), "DEPOSIT_LINK_CANCEL_EXHAUSTED",
                    "Exceeded 10 cancellation attempts for payment link: " + payment.getRazorpayLinkId());
            return;
        }

        payment.setCancelAttempts(payment.getCancelAttempts() + 1);
        payment.setUpdatedAt(Instant.now());

        try {
            paymentGateway.cancelPaymentLink(payment.getRazorpayLinkId());
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setCancelRequestedAt(null);
            paymentRepository.save(payment);
        } catch (Exception ex) {
            log.error("Failed retry cancelling link {} attempt {}: {}",
                    payment.getRazorpayLinkId(), payment.getCancelAttempts(), ex.getClass().getSimpleName());
            if (payment.getCancelAttempts() >= 10) {
                payment.setCancelRequestedAt(null);
                auditService.logAction(payment.getDeal(), "DEPOSIT_LINK_CANCEL_EXHAUSTED",
                        "Exceeded 10 cancellation attempts for payment link: " + payment.getRazorpayLinkId());
            }
            paymentRepository.save(payment);
        }
    }
}
