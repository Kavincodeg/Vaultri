package com.vaultri.core.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MonthlyDealsReport(
        String month,
        long dealsCreated,
        long dealsCompleted,
        long dealsCancelled,
        long depositsCollected
) {
    @JsonProperty("depositsPaid")
    public long getDepositsPaid() {
        return depositsCollected;
    }
}
