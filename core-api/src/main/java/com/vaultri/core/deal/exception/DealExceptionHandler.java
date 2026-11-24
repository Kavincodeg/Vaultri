package com.vaultri.core.deal.exception;

import com.vaultri.core.auth.exception.DuplicateEmailException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Maps domain exceptions to RFC 7807 ProblemDetail HTTP responses.
 */
@RestControllerAdvice
public class DealExceptionHandler {

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

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
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
}
