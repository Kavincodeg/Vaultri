package com.vaultri.core.deal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CancelDealRequest(
        @NotNull(message = "requestedFeePaise is required")
        @Min(value = 0, message = "requestedFeePaise must be greater than or equal to 0")
        Long requestedFeePaise,

        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason
) {}
