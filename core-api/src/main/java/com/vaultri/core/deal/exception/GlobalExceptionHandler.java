package com.vaultri.core.deal.exception;

import com.vaultri.core.auth.exception.DuplicateEmailException;
import com.vaultri.core.payment.exception.GatewayUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Maps domain exceptions to RFC 7807 ProblemDetail HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalDealTransitionException.class)
    public ProblemDetail handleIllegalDealTransition(IllegalDealTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Illegal Deal Transition");
        problem.setType(URI.create("https://vaultri.com/errors/illegal-transition"));
        problem.setProperty("from", ex.getFrom());
        problem.setProperty("to", ex.getTo());
        return problem;
    }

    @ExceptionHandler(DealNotFoundException.class)
    public ProblemDetail handleDealNotFound(DealNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Deal Not Found");
        problem.setType(URI.create("https://vaultri.com/errors/deal-not-found"));
        problem.setProperty("dealId", ex.getDealId());
        return problem;
    }

    @ExceptionHandler(InvalidCancellationFeeException.class)
    public ProblemDetail handleInvalidCancellationFee(InvalidCancellationFeeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Cancellation Fee");
        problem.setType(URI.create("https://vaultri.com/errors/invalid-cancellation-fee"));
        return problem;
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicateEmail(DuplicateEmailException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Duplicate Email");
        problem.setType(URI.create("https://vaultri.com/errors/duplicate-email"));
        return problem;
    }

    @ExceptionHandler(com.vaultri.core.report.exception.InvalidDateRangeException.class)
    public ProblemDetail handleInvalidDateRange(com.vaultri.core.report.exception.InvalidDateRangeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Date Range");
        problem.setType(URI.create("https://vaultri.com/errors/invalid-date-range"));
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Request conflicts with existing data"
        );
        problem.setTitle("Conflict");
        problem.setType(URI.create("https://vaultri.com/errors/conflict"));
        return problem;
    }

    @ExceptionHandler(GatewayUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleGatewayUnavailable(GatewayUnavailableException ex) {
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

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The resource was modified concurrently. Please retry."
        );
        problem.setTitle("Concurrent Modification");
        problem.setType(URI.create("https://vaultri.com/errors/concurrent-modification"));
        return problem;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
        problem.setTitle("Authentication Failed");
        problem.setType(URI.create("https://vaultri.com/errors/unauthorized"));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://vaultri.com/errors/validation-error"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://vaultri.com/errors/bad-request"));
        return problem;
    }
}
