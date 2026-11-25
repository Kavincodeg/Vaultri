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
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderKind;
import com.vaultri.core.reminder.entity.ReminderStatus;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Deal lifecycle, terms, and protection management APIs")
public class DealController {

    private final DealService dealService;
    private final DealCreationService dealCreationService;
    private final com.vaultri.core.deal.service.DealCancellationService dealCancellationService;
    private final PaymentRepository paymentRepository;
    private final com.vaultri.core.reminder.repository.ReminderRepository reminderRepository;

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
        Optional<Payment> cancellationFeePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE);
        return ResponseEntity.ok(DealResponse.from(deal, depositPayment.orElse(null), cancellationFeePayment.orElse(null)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel deal", description = "Cancels an active deal with an optional cancellation fee payment link (idempotent)")
    public ResponseEntity<?> cancelDeal(
            @PathVariable UUID id,
            @CurrentSeller UUID sellerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody com.vaultri.core.deal.dto.CancelDealRequest request
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

        return dealCancellationService.cancelDeal(sellerId, id, idempotencyKey.trim(), request);
    }

    @PostMapping("/{id}/reminders")
    @Operation(summary = "Create manual reminder", description = "Creates a PENDING reminder due now for an active deal")
    public ResponseEntity<?> createManualReminder(
            @PathVariable UUID id,
            @CurrentSeller UUID sellerId
    ) {
        Deal deal = dealService.getDeal(sellerId, id);

        Optional<Reminder> existingPending =
                reminderRepository.findByDealIdAndKindAndStatus(deal.getId(), ReminderKind.MANUAL, ReminderStatus.PENDING);
        if (existingPending.isPresent()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "A manual reminder is already pending for this deal"
            );
            problem.setTitle("Conflict");
            problem.setType(URI.create("https://vaultri.com/errors/conflict"));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        Reminder reminder = Reminder.builder()
                .deal(deal)
                .channel("EMAIL")
                .kind(ReminderKind.MANUAL)
                .status(ReminderStatus.PENDING)
                .dueAt(Instant.now())
                .attempts(0)
                .createdAt(Instant.now())
                .build();

        try {
            reminderRepository.saveAndFlush(reminder);
        } catch (DataIntegrityViolationException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "A manual reminder is already pending for this deal"
            );
            problem.setTitle("Conflict");
            problem.setType(URI.create("https://vaultri.com/errors/conflict"));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
