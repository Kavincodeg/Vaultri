package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;

import java.util.Optional;

public interface PaymentGateway {
    PaymentLinkResponse createPaymentLink(CreateLinkRequest request);
    Optional<PaymentLinkResponse> findPaymentLinkByReferenceId(String referenceId);
    void cancelPaymentLink(String linkId);
}
