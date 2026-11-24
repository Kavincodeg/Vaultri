package com.vaultri.core.deal.state;

import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.IllegalDealTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Plain Java State Machine governing the Deal lifecycle.
 * Contains zero Spring or framework dependencies.
 *
 * Allowed transitions:
 * - DRAFT -> DEPOSIT_PENDING, CANCELLED
 * - DEPOSIT_PENDING -> DEPOSIT_PAID, CANCELLED
 * - DEPOSIT_PAID -> IN_PROGRESS, CANCELLED
 * - IN_PROGRESS -> COMPLETED, CANCELLED
 * - COMPLETED -> terminal (no transitions)
 * - CANCELLED -> terminal (no transitions)
 */
public final class DealStateMachine {

    private static final Map<DealStatus, Set<DealStatus>> TRANSITIONS;

    static {
        Map<DealStatus, Set<DealStatus>> map = new EnumMap<>(DealStatus.class);

        map.put(DealStatus.DRAFT, EnumSet.of(DealStatus.DEPOSIT_PENDING, DealStatus.CANCELLED));
        map.put(DealStatus.DEPOSIT_PENDING, EnumSet.of(DealStatus.DEPOSIT_PAID, DealStatus.CANCELLED));
        map.put(DealStatus.DEPOSIT_PAID, EnumSet.of(DealStatus.IN_PROGRESS, DealStatus.CANCELLED));
        map.put(DealStatus.IN_PROGRESS, EnumSet.of(DealStatus.COMPLETED, DealStatus.CANCELLED));
        map.put(DealStatus.COMPLETED, Collections.emptySet());
        map.put(DealStatus.CANCELLED, Collections.emptySet());

        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private DealStateMachine() {
        // Utility / domain state machine class
    }

    /**
     * Checks if a transition between two DealStatus values is permitted.
     *
     * @param from current status
     * @param to   target status
     * @return true if allowed, false otherwise
     */
    public static boolean isAllowed(DealStatus from, DealStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<DealStatus> targets = TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * Executes and validates a state transition, returning the new target status.
     * Throws IllegalDealTransitionException if the transition is prohibited.
     *
     * @param from current status
     * @param to   target status
     * @return target status if valid
     * @throws IllegalDealTransitionException if transition is not permitted
     */
    public static DealStatus transition(DealStatus from, DealStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalDealTransitionException(from, to);
        }
        return to;
    }
}
