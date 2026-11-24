package com.vaultri.core.deal.controller;

import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.service.DealCreationService;
import com.vaultri.core.deal.service.DealService;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Deal lifecycle, terms, and protection management APIs")
public class DealController {

    private final DealService dealService;
    private final DealCreationService dealCreationService;
    private final PaymentRepository paymentRepository;

    @PostMapping
    @Operation(summary = "Create deal", description = "Creates a deal draft with a deposit payment link (idempotent)")
    public ResponseEntity<?> createDeal(
            @CurrentSeller UUID sellerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDealRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.trim().length() > 255) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required and must be between 1 and 255 characters"
            );
            problem.setTitle("Bad Request");
            problem.setType(URI.create("https://vaultri.com/errors/missing-idempotency-key"));
            return ResponseEntity.badRequest().body(problem);
        }

        return dealCreationService.createDeal(sellerId, idempotencyKey.trim(), request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get deal by ID", description = "Retrieves deal details for the authenticated seller")
    public ResponseEntity<DealResponse> getDeal(@PathVariable UUID id, @CurrentSeller UUID sellerId) {
        Deal deal = dealService.getDeal(sellerId, id);
        Optional<Payment> depositPayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
        return ResponseEntity.ok(DealResponse.from(deal, depositPayment.orElse(null)));
    }
}
