package com.vaultri.core.deal.policy;

import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.exception.InvalidCancellationFeeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage-based cancellation fee policy.
 * - DRAFT, DEPOSIT_PENDING: max fee 0 paise (no work committed)
 * - DEPOSIT_PAID, IN_PROGRESS: max fee = configurable percent of price (property vaultri.cancellation.fee-percent, default 50)
 * - COMPLETED, CANCELLED: max fee 0 paise (terminal stages)
 */
@Component
public class StageBasedCancellationFeePolicy implements CancellationFeePolicy {

    private final int feePercent;

    public StageBasedCancellationFeePolicy(
            @Value("${vaultri.cancellation.fee-percent:50}") int feePercent
    ) {
        this.feePercent = feePercent;
    }

    @Override
    public long maxFee(Deal deal) {
        if (deal == null || deal.getStatus() == null || deal.getPrice() == null) {
            return 0L;
        }

        return switch (deal.getStatus()) {
            case DEPOSIT_PAID, IN_PROGRESS -> {
                // Integer math in paise: (price * feePercent) / 100
                yield (deal.getPrice() * feePercent) / 100L;
            }
            case DRAFT, DEPOSIT_PENDING, COMPLETED, CANCELLED -> 0L;
        };
    }

    @Override
    public void validateFee(Deal deal, long requestedFee) {
        if (requestedFee < 0) {
            throw new InvalidCancellationFeeException("Cancellation fee cannot be negative: " + requestedFee);
        }

        long max = maxFee(deal);
        if (requestedFee > max) {
            throw new InvalidCancellationFeeException(
                    "Requested cancellation fee " + requestedFee + " paise exceeds maximum permitted " + max + " paise"
            );
        }
    }
}
