package com.vaultri.core.ledger.service;

import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.ledger.entity.LedgerEntry;
import com.vaultri.core.ledger.repository.LedgerRepository;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ledger Service.
 *
 * Maintains an append-only record of deposits and cancellation-fee payments per deal.
 * There is NO wallet and NO escrow, since Vaultri never holds funds — all payments
 * settle directly into the seller's verified Razorpay merchant account.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerRepository ledgerRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry recordEntry(Deal deal, Seller seller, Payment payment, PaymentType type, Long amount) {
        LedgerEntry entry = LedgerEntry.builder()
                .deal(deal)
                .seller(seller)
                .payment(payment)
                .type(type)
                .amount(amount)
                .createdAt(Instant.now())
                .build();
        return ledgerRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> findByDealId(UUID dealId) {
        return ledgerRepository.findByDealId(dealId);
    }
}
