package com.vaultri.core.ledger.service;

import com.vaultri.core.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
