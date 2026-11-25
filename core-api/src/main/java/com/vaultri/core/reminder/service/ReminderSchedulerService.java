package com.vaultri.core.reminder.service;

import com.vaultri.core.reminder.repository.ReminderRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderSchedulerService {

    private final ReminderRepository reminderRepository;
    private final ReminderDispatchService reminderDispatchService;

    @Getter
    @Setter
    @Value("${vaultri.reminders.max-run-duration:5m}")
    private Duration maxRunDuration = Duration.ofMinutes(5);

    @Scheduled(cron = "${vaultri.reminders.scheduler-cron:0 0 */12 * * *}")
    @SchedulerLock(name = "reminderSchedulerJob", lockAtMostFor = "15m")
    public void processDueReminders() {
        try {
            int maxPerBatch = 50;
            int count = 0;
            Instant deadline = Instant.now().plus(maxRunDuration != null ? maxRunDuration : Duration.ofMinutes(5));
            while (count < maxPerBatch && Instant.now().isBefore(deadline) && reminderDispatchService.claimAndDispatchOneReminder()) {
                count++;
            }
        } catch (Exception e) {
            log.error("Unhandled error in scheduled reminder job: {}", e.getClass().getSimpleName());
        }
    }

    public void processSingleReminder(UUID reminderId) {
        reminderDispatchService.processSingleReminder(reminderId);
    }
}
