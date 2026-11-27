package com.vaultri.core.payment.controller;

import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.payment.dto.PaymentListItemResponse;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment links creation and settlement verification APIs")
public class PaymentController {

    private final PaymentRepository paymentRepository;

    @GetMapping
    @Operation(summary = "List payments", description = "Lists payments for the authenticated seller's deals")
    public ResponseEntity<Page<PaymentListItemResponse>> getPayments(
            @CurrentSeller UUID sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Payment> paymentsPage = paymentRepository.findBySellerId(sellerId, pageable);
        List<PaymentListItemResponse> responses = paymentsPage.getContent().stream()
                .map(PaymentListItemResponse::from)
                .toList();

        return ResponseEntity.ok(new PageImpl<>(responses, pageable, paymentsPage.getTotalElements()));
    }
}
