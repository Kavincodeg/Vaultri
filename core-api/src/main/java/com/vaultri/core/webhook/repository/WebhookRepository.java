package com.vaultri.core.webhook.repository;

import com.vaultri.core.webhook.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByRazorpayEventId(String razorpayEventId);

    @Modifying
    @Query(value = "INSERT INTO webhook_events (id, razorpay_event_id, event_type, payload, received_at) " +
                   "VALUES (:id, :eventId, :eventType, CAST(:payload AS jsonb), :receivedAt) " +
                   "ON CONFLICT (razorpay_event_id) DO NOTHING", nativeQuery = true)
    int insertIfNotExists(
            @Param("id") UUID id,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("payload") String payload,
            @Param("receivedAt") Instant receivedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WebhookEvent w SET w.processedAt = :processedAt WHERE w.razorpayEventId = :eventId")
    int markProcessed(@Param("eventId") String eventId, @Param("processedAt") Instant processedAt);
}
