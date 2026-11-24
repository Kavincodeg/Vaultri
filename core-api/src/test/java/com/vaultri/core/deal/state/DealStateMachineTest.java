package com.vaultri.core.deal.state;

import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.IllegalDealTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DealStateMachineTest {

    private static final Set<String> ALLOWED_TRANSITIONS = Set.of(
            "DRAFT->DEPOSIT_PENDING",
            "DRAFT->CANCELLED",
            "DEPOSIT_PENDING->DEPOSIT_PAID",
            "DEPOSIT_PENDING->CANCELLED",
            "DEPOSIT_PAID->IN_PROGRESS",
            "DEPOSIT_PAID->CANCELLED",
            "IN_PROGRESS->COMPLETED",
            "IN_PROGRESS->CANCELLED"
    );

    static Stream<Arguments> allPossibleTransitions() {
        List<Arguments> all = new ArrayList<>();
        for (DealStatus from : DealStatus.values()) {
            for (DealStatus to : DealStatus.values()) {
                all.add(Arguments.of(from, to));
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPossibleTransitions")
    @DisplayName("Verify every (from, to) state machine transition is correctly allowed or rejected")
    void testEveryTransition(DealStatus from, DealStatus to) {
        boolean isAllowed = ALLOWED_TRANSITIONS.contains(from.name() + "->" + to.name());

        if (isAllowed) {
            DealStatus result = DealStateMachine.transition(from, to);
            assertThat(result).isEqualTo(to);
            assertThat(DealStateMachine.isAllowed(from, to)).isTrue();
        } else {
            assertThat(DealStateMachine.isAllowed(from, to)).isFalse();
            assertThatThrownBy(() -> DealStateMachine.transition(from, to))
                    .isInstanceOf(IllegalDealTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }
}
