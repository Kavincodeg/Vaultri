package com.vaultri.core.deal.policy;

import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.InvalidCancellationFeeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageBasedCancellationFeePolicyTest {

    private final StageBasedCancellationFeePolicy defaultPolicy = new StageBasedCancellationFeePolicy(50);
    private final StageBasedCancellationFeePolicy customPolicy = new StageBasedCancellationFeePolicy(30);

    @ParameterizedTest
    @EnumSource(value = DealStatus.class, names = {"DRAFT", "DEPOSIT_PENDING", "COMPLETED", "CANCELLED"})
    @DisplayName("maxFee is 0 paise for uncommitted or terminal stages")
    void testMaxFeeZeroForUncommittedOrTerminalStages(DealStatus status) {
        Deal deal = Deal.builder()
                .price(100000L) // 1,000 INR
                .status(status)
                .build();

        assertThat(defaultPolicy.maxFee(deal)).isEqualTo(0L);
    }

    @ParameterizedTest
    @EnumSource(value = DealStatus.class, names = {"DEPOSIT_PAID", "IN_PROGRESS"})
    @DisplayName("maxFee calculates configured percent of deal price in paise for committed stages")
    void testMaxFeeCalculatesConfiguredPercent(DealStatus status) {
        Deal deal = Deal.builder()
                .price(100000L) // 1,000 INR in paise
                .status(status)
                .build();

        // 50% of 100,000 = 50,000 paise
        assertThat(defaultPolicy.maxFee(deal)).isEqualTo(50000L);

        // 30% of 100,000 = 30,000 paise
        assertThat(customPolicy.maxFee(deal)).isEqualTo(30000L);
    }

    @Test
    @DisplayName("validateFee accepts requestedFee between 0 and maxFee")
    void testValidateFeeAcceptsValidAmounts() {
        Deal deal = Deal.builder()
                .price(200000L) // 2,000 INR
                .status(DealStatus.IN_PROGRESS)
                .build();

        // Max fee is 100,000 paise (50%)
        defaultPolicy.validateFee(deal, 0L);
        defaultPolicy.validateFee(deal, 50000L);
        defaultPolicy.validateFee(deal, 100000L);
    }

    @Test
    @DisplayName("validateFee rejects negative fee")
    void testValidateFeeRejectsNegative() {
        Deal deal = Deal.builder()
                .price(100000L)
                .status(DealStatus.DEPOSIT_PAID)
                .build();

        assertThatThrownBy(() -> defaultPolicy.validateFee(deal, -1L))
                .isInstanceOf(InvalidCancellationFeeException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("validateFee rejects fee exceeding maxFee")
    void testValidateFeeRejectsExceedingMax() {
        Deal deal = Deal.builder()
                .price(100000L) // max fee is 50,000
                .status(DealStatus.DEPOSIT_PAID)
                .build();

        assertThatThrownBy(() -> defaultPolicy.validateFee(deal, 50001L))
                .isInstanceOf(InvalidCancellationFeeException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("validateFee rejects any fee > 0 in DRAFT or DEPOSIT_PENDING")
    void testValidateFeeRejectsFeeWhenMaxIsZero() {
        Deal deal = Deal.builder()
                .price(100000L)
                .status(DealStatus.DRAFT)
                .build();

        assertThatThrownBy(() -> defaultPolicy.validateFee(deal, 100L))
                .isInstanceOf(InvalidCancellationFeeException.class);
    }
}
