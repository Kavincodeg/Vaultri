package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.exception.GatewayUnavailableException;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Primary
@Profile("test")
public class FakeGateway implements PaymentGateway {

    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicInteger cancelCallCount = new AtomicInteger(0);
    private final AtomicInteger lookupCallCount = new AtomicInteger(0);
    private volatile boolean shouldFail = false;
    private volatile boolean shouldCancelFail = false;
    private volatile boolean shouldLookupFail = false;
    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private final AtomicInteger remainingCancelFailures = new AtomicInteger(0);
    private final AtomicInteger remainingLookupFailures = new AtomicInteger(0);

    // Records created payment links keyed by reference_id (payment UUID)
    private final Map<String, PaymentLinkResponse> linksByReferenceId = new ConcurrentHashMap<>();
    private volatile CreateLinkRequest lastCreateRequest;

    @Override
    public PaymentLinkResponse createPaymentLink(CreateLinkRequest request) {
        callCount.incrementAndGet();
        this.lastCreateRequest = request;

        String refId = request.paymentId().toString();

        if (linksByReferenceId.containsKey(refId)) {
            // Razorpay rule: A second create with the same reference_id returns 400 Bad Request
            // In our system, 4xx from gateway maps to GatewayUnavailableException
            throw new GatewayUnavailableException("payment link creation with reference ID already attempted");
        }

        if (shouldFail || remainingFailures.getAndDecrement() > 0) {
            throw new GatewayUnavailableException("Simulated gateway unavailable error");
        }

        String linkId = "plink_fake_" + request.paymentId();
        String shortUrl = "https://rzp.io/i/" + linkId;
        PaymentLinkResponse response = new PaymentLinkResponse(linkId, shortUrl);
        linksByReferenceId.put(refId, response);
        return response;
    }

    @Override
    public Optional<PaymentLinkResponse> findPaymentLinkByReferenceId(String referenceId) {
        lookupCallCount.incrementAndGet();

        if (shouldLookupFail || remainingLookupFailures.getAndDecrement() > 0) {
            throw new GatewayUnavailableException("Simulated gateway lookup error");
        }

        return Optional.ofNullable(linksByReferenceId.get(referenceId));
    }

    @Override
    public void cancelPaymentLink(String linkId) {
        cancelCallCount.incrementAndGet();

        if (shouldCancelFail || remainingCancelFailures.getAndDecrement() > 0) {
            throw new GatewayUnavailableException("Simulated gateway cancel unavailable error");
        }
    }

    /**
     * Seeds an existing link in the gateway without going through createPaymentLink.
     * Useful for simulating lost responses where creation succeeded on gateway but client experienced a timeout.
     */
    public void seedLink(String referenceId, String linkId, String shortUrl) {
        seedLink(referenceId, linkId, shortUrl, "created");
    }

    public void seedLink(String referenceId, String linkId, String shortUrl, String status) {
        linksByReferenceId.put(referenceId, new PaymentLinkResponse(linkId, shortUrl, status));
    }

    public int getCallCount() {
        return callCount.get();
    }

    public int getCancelCallCount() {
        return cancelCallCount.get();
    }

    public int getLookupCallCount() {
        return lookupCallCount.get();
    }

    public CreateLinkRequest getLastCreateRequest() {
        return lastCreateRequest;
    }

    public int getStoredLinkCount() {
        return linksByReferenceId.size();
    }

    public void reset() {
        callCount.set(0);
        cancelCallCount.set(0);
        lookupCallCount.set(0);
        shouldFail = false;
        shouldCancelFail = false;
        shouldLookupFail = false;
        remainingFailures.set(0);
        remainingCancelFailures.set(0);
        remainingLookupFailures.set(0);
        linksByReferenceId.clear();
        lastCreateRequest = null;
    }

    public void setShouldFail(boolean fail) {
        this.shouldFail = fail;
    }

    public void setShouldCancelFail(boolean fail) {
        this.shouldCancelFail = fail;
    }

    public void setShouldLookupFail(boolean fail) {
        this.shouldLookupFail = fail;
    }

    public void setFailTimes(int count) {
        this.remainingFailures.set(count);
    }

    public void setCancelFailTimes(int count) {
        this.remainingCancelFailures.set(count);
    }

    public void setLookupFailTimes(int count) {
        this.remainingLookupFailures.set(count);
    }
}
