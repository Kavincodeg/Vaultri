package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.exception.GatewayUnavailableException;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
@Primary
@Profile("test")
public class FakeGateway implements PaymentGateway {

    private final AtomicInteger callCount = new AtomicInteger(0);
    private volatile boolean shouldFail = false;
    private final AtomicInteger remainingFailures = new AtomicInteger(0);

    @Override
    public PaymentLinkResponse createPaymentLink(CreateLinkRequest request) {
        callCount.incrementAndGet();

        if (shouldFail || remainingFailures.getAndDecrement() > 0) {
            throw new GatewayUnavailableException("Simulated gateway unavailable error");
        }

        String linkId = "plink_fake_" + request.paymentId();
        String shortUrl = "https://rzp.io/i/" + linkId;
        return new PaymentLinkResponse(linkId, shortUrl);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void reset() {
        callCount.set(0);
        shouldFail = false;
        remainingFailures.set(0);
    }

    public void setShouldFail(boolean fail) {
        this.shouldFail = fail;
    }

    public void setFailTimes(int count) {
        this.remainingFailures.set(count);
    }
}
