package com.vaultri.core.deal.service;

import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.dto.CreateDealCommand;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.DealNotFoundException;
import com.vaultri.core.deal.policy.CancellationFeePolicy;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.state.DealStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deal Lifecycle Service.
 *
 * Enforces:
 * - Strict seller scoping via findByIdAndSellerId to eliminate BOLA / cross-seller access.
 * - State transitions validated through DealStateMachine.
 * - Atomic audit logging via AuditService.
 * - Non-floating-point integer math for all monetary deposit computations.
 * - Uncaught propagation of OptimisticLockingFailureException for concurrent update conflicts.
 */
@Service
@RequiredArgsConstructor
public class DealService {

    private final DealRepository dealRepository;
    private final SellerRepository sellerRepository;
    private final AuditService auditService;
    private final CancellationFeePolicy cancellationFeePolicy;
    private final com.vaultri.core.reminder.repository.ReminderRepository reminderRepository;

    /**
     * Creates a new draft deal for the given seller.
     * Computes deposit_amount in paise using integer math with half-up rounding:
     *   deposit_amount = (price * deposit_percent + 50) / 100
     */
    @Transactional
    public Deal createDraft(UUID sellerId, CreateDealCommand command) {
        if (command.getPrice() == null || command.getPrice() <= 0) {
            throw new IllegalArgumentException("Price must be greater than zero");
        }
        if (command.getDepositPercent() == null || command.getDepositPercent() < 0 || command.getDepositPercent() > 100) {
            throw new IllegalArgumentException("Deposit percent must be between 0 and 100");
        }

        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Seller not found: " + sellerId));

        // Rounding rule: half-up integer rounding in paise (avoiding floating-point inaccuracies)
        long depositAmount = (command.getPrice() * command.getDepositPercent() + 50L) / 100L;

        Deal deal = Deal.builder()
                .seller(seller)
                .customerName(command.getCustomerName())
                .customerEmail(command.getCustomerEmail())
                .customerPhone(command.getCustomerPhone())
                .title(command.getTitle())
                .price(command.getPrice())
                .depositPercent(command.getDepositPercent())
                .depositAmount(depositAmount)
                .dueDate(command.getDueDate())
                .status(DealStatus.DRAFT)
                .contractText(command.getContractText())
                .build();

        Deal saved = dealRepository.save(deal);
        auditService.logAction(
                saved,
                "DEAL_CREATED",
                "Draft deal created with price " + saved.getPrice() + " paise and deposit " + saved.getDepositPercent() + "%"
        );
        return saved;
    }

    /**
     * Transitions deal from DRAFT to DEPOSIT_PENDING.
     */
    @Transactional
    public Deal markDepositPending(UUID dealId, UUID sellerId) {
        Deal deal = loadDeal(dealId, sellerId);
        DealStateMachine.transition(deal.getStatus(), DealStatus.DEPOSIT_PENDING);

        deal.setStatus(DealStatus.DEPOSIT_PENDING);
        Deal saved = dealRepository.save(deal);
        auditService.logAction(saved, "DEPOSIT_PENDING", "Deposit payment link generated/pending");

        // When a deal moves to DEPOSIT_PENDING and has a due date, create ONE reminder (channel EMAIL, PENDING)
        // with due_at = dueDate minus 3 days, or now() + 1 hour if that is already in the past.
        if (saved.getDueDate() != null) {
            java.time.Instant now = java.time.Instant.now();
            java.time.Instant targetDue = saved.getDueDate().minus(3, java.time.temporal.ChronoUnit.DAYS);
            java.time.Instant dueAt = targetDue.isBefore(now) ? now.plus(1, java.time.temporal.ChronoUnit.HOURS) : targetDue;

            com.vaultri.core.reminder.entity.Reminder reminder = com.vaultri.core.reminder.entity.Reminder.builder()
                    .deal(saved)
                    .channel("EMAIL")
                    .status(com.vaultri.core.reminder.entity.ReminderStatus.PENDING)
                    .dueAt(dueAt)
                    .attempts(0)
                    .createdAt(now)
                    .build();
            reminderRepository.save(reminder);
        }

        return saved;
    }

    /**
     * Transitions deal from DEPOSIT_PENDING to DEPOSIT_PAID.
     */
    @Transactional
    public Deal markDepositPaid(UUID dealId, UUID sellerId) {
        Deal deal = loadDeal(dealId, sellerId);
        DealStateMachine.transition(deal.getStatus(), DealStatus.DEPOSIT_PAID);

        deal.setStatus(DealStatus.DEPOSIT_PAID);
        Deal saved = dealRepository.save(deal);
        auditService.logAction(saved, "DEPOSIT_PAID", "Deposit confirmed and paid");
        return saved;
    }

    /**
     * Transitions deal from DEPOSIT_PAID to IN_PROGRESS.
     */
    @Transactional
    public Deal startProgress(UUID dealId, UUID sellerId) {
        Deal deal = loadDeal(dealId, sellerId);
        DealStateMachine.transition(deal.getStatus(), DealStatus.IN_PROGRESS);

        deal.setStatus(DealStatus.IN_PROGRESS);
        Deal saved = dealRepository.save(deal);
        auditService.logAction(saved, "IN_PROGRESS", "Work on deal started");
        return saved;
    }

    /**
     * Transitions deal from IN_PROGRESS to COMPLETED.
     */
    @Transactional
    public Deal complete(UUID dealId, UUID sellerId) {
        Deal deal = loadDeal(dealId, sellerId);
        DealStateMachine.transition(deal.getStatus(), DealStatus.COMPLETED);

        deal.setStatus(DealStatus.COMPLETED);
        Deal saved = dealRepository.save(deal);
        reminderRepository.cancelPendingByDealId(saved.getId());
        auditService.logAction(saved, "COMPLETED", "Deal marked as completed");
        return saved;
    }

    /**
     * Cancels a deal, validating the requested cancellation fee against the fee policy.
     */
    @Transactional
    public Deal cancel(UUID dealId, UUID sellerId, long cancellationFee) {
        Deal deal = loadDeal(dealId, sellerId);

        cancellationFeePolicy.validateFee(deal, cancellationFee);
        DealStateMachine.transition(deal.getStatus(), DealStatus.CANCELLED);

        deal.setStatus(DealStatus.CANCELLED);
        Deal saved = dealRepository.save(deal);
        reminderRepository.cancelPendingByDealId(saved.getId());

        String detail = "Deal cancelled with cancellation fee " + cancellationFee + " paise";
        auditService.logAction(saved, "CANCELLED", detail);
        return saved;
    }

    /**
     * Overload for cancellation with 0 cancellation fee.
     */
    @Transactional
    public Deal cancel(UUID dealId, UUID sellerId) {
        return cancel(dealId, sellerId, 0L);
    }

    /**
     * Retrieves a deal scoped strictly to the authenticated seller.
     */
    @Transactional(readOnly = true)
    public Deal getDeal(UUID sellerId, UUID dealId) {
        return loadDeal(dealId, sellerId);
    }

    /**
     * Helper to load deals ONLY by id AND sellerId, guaranteeing cross-seller isolation.
     */
    private Deal loadDeal(UUID dealId, UUID sellerId) {
        return dealRepository.findByIdAndSellerId(dealId, sellerId)
                .orElseThrow(() -> new DealNotFoundException(dealId));
    }
}
