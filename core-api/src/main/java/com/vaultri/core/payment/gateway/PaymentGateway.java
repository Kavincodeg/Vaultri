package com.vaultri.core.payment.gateway;

import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.gateway.dto.PaymentLinkResponse;

public interface PaymentGateway {
    PaymentLinkResponse createPaymentLink(CreateLinkRequest request);
}
