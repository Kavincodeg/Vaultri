package com.vaultri.core.deal.controller;

import com.vaultri.core.audit.dto.AuditLogResponse;
import com.vaultri.core.audit.entity.AuditLog;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.auth.security.CurrentSeller;
import com.vaultri.core.common.dto.PageResponse;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.DealNotFoundException;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.service.DealCreationService;
import com.vaultri.core.deal.service.DealService;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderKind;
import com.vaultri.core.reminder.entity.ReminderStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/deals")
@RequiredArgsConstructor
@Tag(name = "Deals", description = "Deal lifecycle, terms, and protection management APIs")
public class DealController {

    private final DealService dealService;
    private final DealCreationService dealCreationService;
    private final com.vaultri.core.deal.service.DealCancellationService dealCancellationService;
    private final DealRepository dealRepository;
    private final PaymentRepository paymentRepository;
    private final com.vaultri.core.reminder.repository.ReminderRepository reminderRepository;
    private final AuditRepository auditRepository;

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

    @GetMapping
    @Operation(summary = "List deals", description = "Lists deals for the authenticated seller with pagination and optional status filter")
    public ResponseEntity<PageResponse<DealResponse>> getDeals(
            @CurrentSeller UUID sellerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }

        DealStatus dealStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                dealStatus = DealStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status: " + status);
            }
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Deal> dealsPage;
        if (dealStatus != null) {
            dealsPage = dealRepository.findBySellerIdAndStatus(sellerId, dealStatus, pageable);
        } else {
            dealsPage = dealRepository.findBySellerId(sellerId, pageable);
        }

        List<UUID> dealIds = dealsPage.getContent().stream().map(Deal::getId).toList();
        Map<UUID, List<Payment>> paymentsByDealId;
        if (!dealIds.isEmpty()) {
            List<Payment> payments = paymentRepository.findByDealIdIn(dealIds);
            paymentsByDealId = payments.stream().collect(Collectors.groupingBy(p -> p.getDeal().getId()));
        } else {
            paymentsByDealId = Map.of();
        }

        List<DealResponse> responses = dealsPage.getContent().stream().map(deal -> {
            List<Payment> dealPayments = paymentsByDealId.getOrDefault(deal.getId(), List.of());
            Payment deposit = dealPayments.stream().filter(p -> p.getType() == PaymentType.DEPOSIT).findFirst().orElse(null);
            Payment fee = dealPayments.stream().filter(p -> p.getType() == PaymentType.CANCELLATION_FEE).findFirst().orElse(null);
            return DealResponse.from(deal, deposit, fee);
        }).toList();

        return ResponseEntity.ok(PageResponse.from(dealsPage, responses));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get deal by ID", description = "Retrieves deal details for the authenticated seller")
    public ResponseEntity<DealResponse> getDeal(@PathVariable UUID id, @CurrentSeller UUID sellerId) {
        Deal deal = dealService.getDeal(sellerId, id);
        Optional<Payment> depositPayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
        Optional<Payment> cancellationFeePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE);
        return ResponseEntity.ok(DealResponse.from(deal, depositPayment.orElse(null), cancellationFeePayment.orElse(null)));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Start deal work", description = "Transitions deal from DEPOSIT_PAID to IN_PROGRESS")
    public ResponseEntity<DealResponse> startDeal(
            @PathVariable UUID id,
            @CurrentSeller UUID sellerId
    ) {
        Deal deal = dealService.startProgress(id, sellerId);
        Optional<Payment> depositPayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT);
        Optional<Payment> cancellationFeePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE);
        return ResponseEntity.ok(DealResponse.from(deal, depositPayment.orElse(null), cancellationFeePayment.orElse(null)));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Complete deal", description = "Transitions deal from IN_PROGRESS to COMPLETED")
    public ResponseEntity<DealResponse> completeDeal(
            @PathVariable UUID id,
            @CurrentSeller UUID sellerId
    ) {
        Deal deal = dealService.complete(id, sellerId);
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

    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "Get deal audit logs", description = "Retrieves audit trail for a specific deal owned by authenticated seller")
    public ResponseEntity<PageResponse<AuditLogResponse>> getDealAuditLogs(
            @PathVariable UUID id,
            @CurrentSeller UUID sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }

        // Deal ownership check - throws DealNotFoundException (404) if deal not owned by seller or doesn't exist
        dealRepository.findByIdAndSellerId(id, sellerId).orElseThrow(() -> new DealNotFoundException(id));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AuditLog> auditLogsPage = auditRepository.findByDealIdAndSellerId(id, sellerId, pageable);
        List<AuditLogResponse> responses = auditLogsPage.getContent().stream()
                .map(AuditLogResponse::from)
                .toList();

        return ResponseEntity.ok(PageResponse.from(auditLogsPage, responses));
    }
}
