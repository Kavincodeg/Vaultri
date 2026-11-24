package com.vaultri.core.deal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Maps Deal domain exceptions to RFC 7807 ProblemDetail HTTP responses.
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
}
