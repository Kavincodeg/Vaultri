package com.vaultri.core.webhook.repository;

import com.vaultri.core.webhook.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByRazorpayEventId(String razorpayEventId);
}
