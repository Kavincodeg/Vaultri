package com.vaultri.core.deal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record CreateDealRequest(
        @NotBlank(message = "Customer name is required")
        String customerName,

        @Email(message = "Invalid customer email format")
        String customerEmail,

        String customerPhone,

        @NotBlank(message = "Title is required")
        String title,

        @NotNull(message = "Price is required")
        @Positive(message = "Price must be greater than zero")
        Long price,

        @NotNull(message = "Deposit percent is required")
        @Min(value = 1, message = "Deposit percent must be between 1 and 100")
        @Max(value = 100, message = "Deposit percent must be between 1 and 100")
        Integer depositPercent,

        Instant dueDate,

        String contractText
) {}
