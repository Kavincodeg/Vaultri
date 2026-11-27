package com.vaultri.core.audit.repository;

import com.vaultri.core.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByDealId(UUID dealId);

    @Query(value = "SELECT a FROM AuditLog a JOIN FETCH a.deal d WHERE d.seller.id = :sellerId",
           countQuery = "SELECT count(a) FROM AuditLog a JOIN a.deal d WHERE d.seller.id = :sellerId")
    Page<AuditLog> findBySellerId(@Param("sellerId") UUID sellerId, Pageable pageable);

    @Query(value = "SELECT a FROM AuditLog a JOIN FETCH a.deal d WHERE d.id = :dealId AND d.seller.id = :sellerId",
           countQuery = "SELECT count(a) FROM AuditLog a JOIN a.deal d WHERE d.id = :dealId AND d.seller.id = :sellerId")
    Page<AuditLog> findByDealIdAndSellerId(@Param("dealId") UUID dealId, @Param("sellerId") UUID sellerId, Pageable pageable);
}
