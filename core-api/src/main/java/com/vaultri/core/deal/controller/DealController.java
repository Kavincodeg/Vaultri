package com.vaultri.core.deal.controller;

import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.service.DealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Deal lifecycle, terms, and protection management APIs")
public class DealController {

    private final DealService dealService;

    @GetMapping("/{id}")
    @Operation(summary = "Get deal by ID", description = "Retrieves deal details for the authenticated seller")
    public ResponseEntity<DealResponse> getDeal(@PathVariable UUID id, @CurrentSeller UUID sellerId) {
        Deal deal = dealService.getDeal(sellerId, id);
        return ResponseEntity.ok(DealResponse.from(deal));
    }
}
