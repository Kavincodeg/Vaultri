package com.vaultri.core.payment.repository;

import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByRazorpayLinkId(String razorpayLinkId);

    @Query("SELECT p FROM Payment p JOIN FETCH p.deal d JOIN FETCH d.seller WHERE p.razorpayLinkId = :razorpayLinkId")
    Optional<Payment> findByRazorpayLinkIdWithDealAndSeller(@Param("razorpayLinkId") String razorpayLinkId);
    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);
    Optional<Payment> findByDealIdAndType(UUID dealId, PaymentType type);
    List<Payment> findByDealId(UUID dealId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Payment p SET p.status = 'PAID', p.razorpayPaymentId = :paymentId, p.updatedAt = :now WHERE p.id = :id AND p.status = 'CREATED'")
    int markPaidIfCreated(@Param("id") UUID id, @Param("paymentId") String paymentId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Payment p SET p.status = :targetStatus, p.updatedAt = :now WHERE p.id = :id AND p.status = 'CREATED'")
    int updateStatusIfCreated(@Param("id") UUID id, @Param("targetStatus") PaymentStatus targetStatus, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Payment p SET p.status = 'CANCELLED', p.cancelRequestedAt = NULL, p.updatedAt = :now WHERE p.id = :id AND p.status = 'CREATED'")
    int markCancelledAndClearCancelRequestedIfCreated(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Payment p SET p.cancelRequestedAt = :now, p.updatedAt = :now WHERE p.id = :id AND p.status = 'CREATED'")
    int setCancelRequestedAtIfCreated(@Param("id") UUID id, @Param("now") Instant now);

    @Query("SELECT p FROM Payment p JOIN FETCH p.deal d JOIN FETCH d.seller WHERE p.cancelRequestedAt IS NOT NULL AND p.status = 'CREATED' AND p.cancelAttempts < 10 ORDER BY p.cancelRequestedAt ASC")
    List<Payment> findPendingCancellations();
}
