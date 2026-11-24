package com.vaultri.core.ledger.repository;

import com.vaultri.core.ledger.entity.LedgerEntry;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface LedgerRepository extends Repository<LedgerEntry, UUID> {
    LedgerEntry save(LedgerEntry entity);
    Optional<LedgerEntry> findById(UUID id);
    List<LedgerEntry> findByDealId(UUID dealId);
    List<LedgerEntry> findBySellerId(UUID sellerId);
    List<LedgerEntry> findAll();
}
