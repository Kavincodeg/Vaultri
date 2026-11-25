package com.vaultri.core.reminder.repository;

import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    List<Reminder> findByDealId(UUID dealId);

    @Modifying
    @Query("UPDATE Reminder r SET r.status = 'CANCELLED' WHERE r.deal.id = :dealId AND r.status = 'PENDING'")
    int cancelPendingByDealId(@Param("dealId") UUID dealId);

    Optional<Reminder> findByDealIdAndStatus(UUID dealId, ReminderStatus status);

    @Query("SELECT r FROM Reminder r JOIN FETCH r.deal d JOIN FETCH d.seller WHERE r.id = :id")
    Optional<Reminder> findByIdWithDealAndSeller(@Param("id") UUID id);

    @Query(value = "SELECT id FROM reminders WHERE status = 'PENDING' AND due_at <= :now ORDER BY due_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> findDueReminderIdsForUpdate(@Param("now") java.time.Instant now, @Param("limit") int limit);
}
