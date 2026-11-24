package com.vaultri.core.deal.policy;

import com.vaultri.core.deal.entity.Deal;

/**
 * Strategy interface governing cancellation fee calculations and validation limits.
 */
public interface CancellationFeePolicy {

    /**
     * Computes the maximum allowable cancellation fee in paise for the given deal state.
     *
     * @param deal target deal
     * @return maximum cancellation fee in paise (>= 0)
     */
    long maxFee(Deal deal);

    /**
     * Validates that the requested cancellation fee does not exceed policy limits.
     *
     * @param deal target deal
     * @param requestedFee fee requested in paise
     * @throws com.vaultri.core.deal.exception.InvalidCancellationFeeException if fee is invalid
     */
    void validateFee(Deal deal, long requestedFee);
}
