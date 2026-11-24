package com.vaultri.core.audit.service;

import com.vaultri.core.audit.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
