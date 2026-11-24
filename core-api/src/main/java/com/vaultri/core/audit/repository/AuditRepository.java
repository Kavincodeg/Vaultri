package com.vaultri.core.audit.repository;

import com.vaultri.core.audit.entity.AuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface AuditRepository extends Repository<AuditLog, UUID> {
    AuditLog save(AuditLog entity);
    Optional<AuditLog> findById(UUID id);
    List<AuditLog> findByDealId(UUID dealId);
    List<AuditLog> findAll();
}
