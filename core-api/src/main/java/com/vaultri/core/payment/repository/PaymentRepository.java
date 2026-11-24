package com.vaultri.core.payment.repository;

import com.vaultri.core.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByRazorpayLinkId(String razorpayLinkId);
    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);
}
