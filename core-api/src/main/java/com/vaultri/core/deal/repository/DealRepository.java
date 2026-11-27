package com.vaultri.core.deal.repository;

import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DealRepository extends JpaRepository<Deal, UUID> {
    List<Deal> findBySellerId(UUID sellerId);
    Page<Deal> findBySellerId(UUID sellerId, Pageable pageable);
    Page<Deal> findBySellerIdAndStatus(UUID sellerId, DealStatus status, Pageable pageable);
    Optional<Deal> findByIdAndSellerId(UUID id, UUID sellerId);
}
