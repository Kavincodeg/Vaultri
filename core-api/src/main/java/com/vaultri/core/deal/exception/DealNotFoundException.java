package com.vaultri.core.deal.exception;

import java.util.UUID;

public class DealNotFoundException extends RuntimeException {

    private final UUID dealId;

    public DealNotFoundException(UUID dealId) {
        super("Deal not found: " + dealId);
        this.dealId = dealId;
    }

    public UUID getDealId() {
        return dealId;
    }
}
