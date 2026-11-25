package com.vaultri.core.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record SummaryReportResponse(
        Map<String, Long> dealsByStatus,
        long totalDeals,
        long totalDealValue,
        long totalDepositsPaid,
        long totalCancellationFeesCollected,
        long cancelledDealsCount
) {
    @JsonProperty("statusCounts")
    public Map<String, Long> getStatusCounts() {
        return dealsByStatus;
    }

    @JsonProperty("totalCancellationFees")
    public long getTotalCancellationFees() {
        return totalCancellationFeesCollected;
    }

    @JsonProperty("cancelledDeals")
    public long getCancelledDeals() {
        return cancelledDealsCount;
    }
}
