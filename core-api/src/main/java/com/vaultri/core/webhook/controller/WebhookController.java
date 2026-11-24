package com.vaultri.core.webhook.controller;

import com.vaultri.core.webhook.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Razorpay and payment provider event receivers APIs")
public class WebhookController {

    private static final int MAX_BODY_BYTES = 256 * 1024; // 256 KB

    private final WebhookService webhookService;

    @PostMapping("/webhooks/razorpay")
    @Operation(summary = "Razorpay Webhook", description = "Receives asynchronous payment and refund lifecycle events from Razorpay")
    public ResponseEntity<?> handleRazorpayWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
            HttpServletRequest request
    ) {
        // Fast-fail before reading if Content-Length header indicates oversized payload
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Payload exceeds maximum allowed size of 256 KB"
            );
            problem.setTitle("Payload Too Large");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
        }

        byte[] bodyBytes;
        try {
            InputStream in = request.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                            HttpStatus.PAYLOAD_TOO_LARGE,
                            "Payload exceeds maximum allowed size of 256 KB"
                    );
                    problem.setTitle("Payload Too Large");
                    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
                }
                baos.write(buffer, 0, read);
            }
            bodyBytes = baos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to read webhook request stream: {}", e.getClass().getSimpleName());
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Failed to read webhook request body"
            );
            problem.setTitle("Bad Request");
            return ResponseEntity.badRequest().body(problem);
        }

        if (bodyBytes.length == 0) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Webhook request body cannot be empty"
            );
            problem.setTitle("Bad Request");
            return ResponseEntity.badRequest().body(problem);
        }

        if (signature == null || signature.isBlank()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED,
                    "Missing or invalid webhook signature"
            );
            problem.setTitle("Unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
        }

        if (!webhookService.verifySignature(bodyBytes, signature)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED,
                    "Webhook signature verification failed"
            );
            problem.setTitle("Unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
        }

        if (eventId == null || eventId.isBlank() || eventId.trim().length() > 255) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Missing or invalid X-Razorpay-Event-Id header"
            );
            problem.setTitle("Bad Request");
            return ResponseEntity.badRequest().body(problem);
        }

        try {
            webhookService.processWebhook(eventId.trim(), bodyBytes);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "Invalid webhook request payload"
            );
            problem.setTitle("Bad Request");
            return ResponseEntity.badRequest().body(problem);
        } catch (Exception e) {
            log.error("Internal server error processing webhook event: {}, exception: {}", eventId.trim(), e.getClass().getSimpleName());
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "An unexpected error occurred processing the webhook event"
            );
            problem.setTitle("Internal Server Error");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }
    }
}
