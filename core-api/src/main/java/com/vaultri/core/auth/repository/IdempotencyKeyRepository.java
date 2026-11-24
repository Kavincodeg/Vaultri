package com.vaultri.core.auth.repository;

import com.vaultri.core.auth.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    @Query("SELECT k FROM IdempotencyKey k LEFT JOIN FETCH k.deal d LEFT JOIN FETCH d.seller WHERE k.seller.id = :sellerId AND k.key = :key")
    Optional<IdempotencyKey> findBySellerIdAndKeyWithDeal(@Param("sellerId") UUID sellerId, @Param("key") String key);

    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (id, seller_id, key, request_hash, created_at, locked_until) " +
                   "VALUES (:id, :sellerId, :key, :requestHash, :createdAt, :lockedUntil) " +
                   "ON CONFLICT (seller_id, key) DO NOTHING", nativeQuery = true)
    int insertIfNotExists(
            @Param("id") UUID id,
            @Param("sellerId") UUID sellerId,
            @Param("key") String key,
            @Param("requestHash") String requestHash,
            @Param("createdAt") Instant createdAt,
            @Param("lockedUntil") Instant lockedUntil
    );

    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.lockedUntil = :newLock WHERE k.seller.id = :sellerId AND k.key = :key AND (k.lockedUntil IS NULL OR k.lockedUntil < :now) AND k.responseStatus IS NULL")
    int tryAcquireLock(@Param("sellerId") UUID sellerId, @Param("key") String key, @Param("now") Instant now, @Param("newLock") Instant newLock);

    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.lockedUntil = NULL WHERE k.seller.id = :sellerId AND k.key = :key")
    int releaseLock(@Param("sellerId") UUID sellerId, @Param("key") String key);
}
