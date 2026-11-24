package com.vaultri.core.audit.service;

import com.vaultri.core.audit.entity.AuditLog;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.deal.entity.Deal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Audit Service.
 *
 * Records immutable, append-only audit trail logs for all deal lifecycle actions,
 * contract generation events, payment updates, and reminder dispatches.
 * Audit history is exposed through deal endpoints rather than standalone controllers.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository auditRepository;

    /**
     * Appends an immutable audit log record for a deal action.
     *
     * @param deal   associated deal
     * @param action lifecycle action name (e.g. DEAL_CREATED, DEPOSIT_PENDING)
     * @param detail descriptive detail of the change
     * @return persisted AuditLog
     */
    public AuditLog logAction(Deal deal, String action, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .deal(deal)
                .action(action)
                .detail(detail)
                .createdAt(Instant.now())
                .build();
        return auditRepository.save(auditLog);
    }
}
