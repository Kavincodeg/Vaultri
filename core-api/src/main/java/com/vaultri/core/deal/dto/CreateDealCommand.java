package com.vaultri.core.deal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDealCommand {
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String title;
    private Long price;
    private Integer depositPercent;
    private Instant dueDate;
    private String contractText;
}
