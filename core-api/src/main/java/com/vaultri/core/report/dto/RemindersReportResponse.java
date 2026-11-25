package com.vaultri.core.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record RemindersReportResponse(
        Map<String, Long> byStatus,
        Map<String, Long> byKind,
        Map<String, Map<String, Long>> byStatusAndKind,
        long total
) {
    @JsonProperty("statusCounts")
    public Map<String, Long> getStatusCounts() {
        return byStatus;
    }

    @JsonProperty("kindCounts")
    public Map<String, Long> getKindCounts() {
        return byKind;
    }
}
