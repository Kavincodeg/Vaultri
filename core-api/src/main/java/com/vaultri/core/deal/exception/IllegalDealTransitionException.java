package com.vaultri.core.deal.exception;

import com.vaultri.core.deal.entity.DealStatus;

public class IllegalDealTransitionException extends RuntimeException {

    private final DealStatus from;
    private final DealStatus to;

    public IllegalDealTransitionException(DealStatus from, DealStatus to) {
        super("Illegal deal transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public DealStatus getFrom() {
        return from;
    }

    public DealStatus getTo() {
        return to;
    }
}
