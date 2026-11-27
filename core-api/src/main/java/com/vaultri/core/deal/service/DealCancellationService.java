package com.vaultri.core.deal.service;

import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentType;
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
public class DealCancellationService {

    private final DealCancellationStepService stepService;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;

    public String computeRequestHash(CancelDealRequest req) {
        try {
            String canonical = String.format("requestedFeePaise=%d&reason=%s",
                    req.requestedFeePaise(),
                    req.reason() != null ? req.reason().trim() : ""
            );
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public ResponseEntity<?> cancelDeal(UUID sellerId, UUID dealId, String key, CancelDealRequest request) {
        String requestHash = computeRequestHash(request);

        DealCancellationStepService.StepAResult stepA = stepService.executeStepA(sellerId, dealId, key, requestHash, request);

        if (stepA.isDifferentHashConflict()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "This Idempotency-Key was already used with a different request payload."
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
        Payment depositPaymentToCancel = stepA.getDepositPaymentToCancel();
        Payment feePayment = stepA.getFeePayment();

        // Step B1: Cancel unpaid deposit link at gateway if present
        if (depositPaymentToCancel != null && depositPaymentToCancel.getRazorpayLinkId() != null) {
            try {
                paymentGateway.cancelPaymentLink(depositPaymentToCancel.getRazorpayLinkId());
                stepService.markDepositCancelled(depositPaymentToCancel.getId());
            } catch (Exception ex) {
                stepService.recordDepositCancelPending(deal.getId());
            }
        }

        // Step B2: If fee > 0, create fee payment link at gateway
        PaymentLinkResponse feeLinkResponse = null;
        if (feePayment != null) {
            CreateLinkRequest linkRequest = new CreateLinkRequest(
                    feePayment.getId(),
                    feePayment.getAmount(),
                    deal.getTitle(),
                    deal.getId(),
                    null // Cancellation fee uses default linkExpiryDays
            );

            try {
                if (stepA.isFeePaymentRecovered()) {
                    // Recovered fee payment row: look up link at Razorpay by reference_id
                    Optional<PaymentLinkResponse> existingLinkOpt = paymentGateway.findPaymentLinkByReferenceId(feePayment.getId().toString());
                    if (existingLinkOpt.isPresent()) {
                        PaymentLinkResponse existingLink = existingLinkOpt.get();
                        if ("created".equalsIgnoreCase(existingLink.status())) {
                            feeLinkResponse = existingLink;
                        } else {
                            // Existing link is not in "created" status. Do NOT adopt and do NOT create another link.
                            auditService.logAction(deal, "PAYMENT_LINK_NEEDS_REVIEW",
                                    String.format("Payment %s has existing gateway link with status %s", feePayment.getId(), existingLink.status()));
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
                        feeLinkResponse = paymentGateway.createPaymentLink(linkRequest);
                    }
                } else {
                    // Fresh fee payment row: goes straight to createPaymentLink without lookup
                    feeLinkResponse = paymentGateway.createPaymentLink(linkRequest);
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
        }

        DealResponse response = stepService.executeStepB(
                sellerId,
                key,
                deal.getId(),
                feePayment != null ? feePayment.getId() : null,
                feeLinkResponse
        );

        return ResponseEntity.ok(response);
    }
}
