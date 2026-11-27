package com.vaultri.core.deal.service;

import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.exception.GatewayUnavailableException;
import com.vaultri.core.payment.gateway.PaymentGateway;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DealCreationService {

    private final DealCreationStepService stepService;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;

    public String computeRequestHash(CreateDealRequest req) {
        try {
            String canonical = String.format("customerName=%s&customerEmail=%s&customerPhone=%s&title=%s&price=%d&depositPercent=%d&dueDate=%s&contractText=%s",
                    req.customerName().trim(),
                    req.customerEmail() != null ? req.customerEmail().trim().toLowerCase() : "",
                    req.customerPhone() != null ? req.customerPhone().trim() : "",
                    req.title().trim(),
                    req.price(),
                    req.depositPercent(),
                    req.dueDate() != null ? req.dueDate().toString() : "",
                    req.contractText() != null ? req.contractText().trim() : ""
            );
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Orchestrates deal creation: Step A (@Transactional) -> Gateway Call (non-transactional) -> Step B (@Transactional).
     */
    public ResponseEntity<?> createDeal(UUID sellerId, String key, CreateDealRequest request) {
        String requestHash = computeRequestHash(request);
        DealCreationStepService.StepAResult stepA = stepService.executeStepA(sellerId, key, requestHash, request);

        if (stepA.isDifferentHashConflict()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key was previously used with a different request payload"
            );
            problem.setTitle("Idempotency Conflict");
            problem.setType(URI.create("https://vaultri.com/errors/idempotency-conflict"));
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
        }

        if (stepA.isAlreadyCompleted()) {
            return ResponseEntity.status(stepA.getStoredStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stepA.getStoredBody());
        }

        if (stepA.isInProgressNoResume()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "A request with this Idempotency-Key is currently in progress. Please retry shortly."
            );
            problem.setTitle("Request In Progress");
            problem.setType(URI.create("https://vaultri.com/errors/request-in-progress"));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(problem);
        }

        Deal deal = stepA.getDeal();
        Payment payment = stepA.getPayment();

        CreateLinkRequest linkRequest = new CreateLinkRequest(
                payment.getId(),
                payment.getAmount(),
                deal.getTitle(),
                deal.getId(),
                deal.getDueDate()
        );

        PaymentLinkResponse linkResponse;
        try {
            if (stepA.isRecovered()) {
                // Recovered payment row: look up link at Razorpay by reference_id
                Optional<PaymentLinkResponse> existingLinkOpt = paymentGateway.findPaymentLinkByReferenceId(payment.getId().toString());
                if (existingLinkOpt.isPresent()) {
                    PaymentLinkResponse existingLink = existingLinkOpt.get();
                    if ("created".equalsIgnoreCase(existingLink.status())) {
                        linkResponse = existingLink;
                    } else {
                        // Existing link is not in "created" status (e.g. cancelled, expired, paid, partially_paid).
                        // Do NOT adopt and do NOT create another link.
                        auditService.logAction(deal, "PAYMENT_LINK_NEEDS_REVIEW",
                                String.format("Payment %s has existing gateway link with status %s", payment.getId(), existingLink.status()));
                        stepService.releaseLockOnFailure(sellerId, key);
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_GATEWAY,
                                "Payment gateway is temporarily unavailable. Please retry with the same Idempotency-Key."
                        );
                        problem.setTitle("Gateway Unavailable");
                        problem.setType(URI.create("https://vaultri.com/errors/gateway-unavailable"));
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .header(HttpHeaders.RETRY_AFTER, "1")
                                .body(problem);
                    }
                } else {
                    linkResponse = paymentGateway.createPaymentLink(linkRequest);
                }
            } else {
                // Fresh payment row: goes straight to createPaymentLink without lookup
                linkResponse = paymentGateway.createPaymentLink(linkRequest);
            }
        } catch (GatewayUnavailableException e) {
            stepService.releaseLockOnFailure(sellerId, key);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_GATEWAY,
                    "Payment gateway is temporarily unavailable. Please retry with the same Idempotency-Key."
            );
            problem.setTitle("Gateway Unavailable");
            problem.setType(URI.create("https://vaultri.com/errors/gateway-unavailable"));
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(problem);
        }

        DealResponse response = stepService.executeStepB(sellerId, key, deal.getId(), payment.getId(), linkResponse);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
