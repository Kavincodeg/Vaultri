package com.vaultri.core.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.mail.service.FakeMailSender;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderStatus;
import com.vaultri.core.reminder.repository.ReminderRepository;
import com.vaultri.core.reminder.service.ReminderSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReminderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private FakeMailSender fakeMailSender;

    @Autowired
    private ReminderSchedulerService reminderSchedulerService;

    @Autowired
    private com.vaultri.core.reminder.service.ReminderDispatchService reminderDispatchService;

    private String tokenA;
    private String tokenB;
    private String emailA;

    @BeforeEach
    void setUp() throws Exception {
        reminderRepository.deleteAll();
        fakeMailSender.reset();

        emailA = "seller_rem_a_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailA, "Password123!", "Seller A", null))));
        tokenA = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(emailA, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();

        String emailB = "seller_rem_b_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailB, "Password123!", "Seller B", null))));
        tokenB = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(emailB, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();
    }

    private UUID createDealWithDueDate(String token, String customerEmail, Instant dueDate) throws Exception {
        CreateDealRequest req = new CreateDealRequest(
                "Customer", customerEmail, null, "Reminder Deal", 120000L, 20, dueDate, "Terms"
        );
        MvcResult res = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText());
    }

    @Test
    @DisplayName("Reminders: due reminder sent exactly once, marked SENT; recipient resolved to customer or fallback seller")
    void testDueReminderSentWithRecipientFallback() throws Exception {
        // Case 1: Customer email present
        String custEmail = "cust_rem_" + UUID.randomUUID() + "@test.com";
        UUID dealId1 = createDealWithDueDate(tokenA, custEmail, Instant.now().plusSeconds(86400 * 5));

        Reminder rem1 = reminderRepository.findByDealId(dealId1).get(0);
        rem1.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(rem1);

        reminderSchedulerService.processDueReminders();

        Reminder rem1After = reminderRepository.findById(rem1.getId()).orElseThrow();
        assertThat(rem1After.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(rem1After.getSentAt()).isNotNull();

        assertThat(fakeMailSender.getSentMails()).hasSize(1);
        assertThat(fakeMailSender.getSentMails().get(0).to()).isEqualTo(custEmail);

        // Case 2: Customer email null -> fall back to seller email
        fakeMailSender.reset();
        UUID dealId2 = createDealWithDueDate(tokenA, null, Instant.now().plusSeconds(86400 * 5));
        Reminder rem2 = reminderRepository.findByDealId(dealId2).get(0);
        rem2.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(rem2);

        reminderSchedulerService.processDueReminders();

        Reminder rem2After = reminderRepository.findById(rem2.getId()).orElseThrow();
        assertThat(rem2After.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(fakeMailSender.getSentMails()).hasSize(1);
        assertThat(fakeMailSender.getSentMails().get(0).to()).isEqualTo(emailA);
    }

    @Test
    @DisplayName("Reminders Concurrency: two scheduler runs started together send each reminder exactly once")
    void testTwoSchedulersConcurrentSendOnce() throws Exception {
        UUID dealId = createDealWithDueDate(tokenA, "concurrent@cust.com", Instant.now().plusSeconds(86400 * 5));
        Reminder rem = reminderRepository.findByDealId(dealId).get(0);
        rem.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(rem);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                latch.await();
                reminderSchedulerService.processDueReminders();
            } catch (Exception ignored) {}
        });

        executor.submit(() -> {
            try {
                latch.await();
                reminderSchedulerService.processDueReminders();
            } catch (Exception ignored) {}
        });

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(fakeMailSender.getSentMails()).hasSize(1);
        Reminder updated = reminderRepository.findById(rem.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    @DisplayName("Reminders Failure: failing mail sender increments attempts, FAILED after 3 attempts")
    void testFailingMailSenderIncrementsAttemptsAndFailsAfter3() throws Exception {
        UUID dealId = createDealWithDueDate(tokenA, "fail@cust.com", Instant.now().plusSeconds(86400 * 5));
        Reminder rem = reminderRepository.findByDealId(dealId).get(0);
        rem.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(rem);

        fakeMailSender.setShouldFail(true);

        // Run 1: attempt 1
        reminderSchedulerService.processDueReminders();
        Reminder r1 = reminderRepository.findById(rem.getId()).orElseThrow();
        assertThat(r1.getAttempts()).isEqualTo(1);
        assertThat(r1.getStatus()).isEqualTo(ReminderStatus.PENDING);

        // Run 2: attempt 2
        reminderSchedulerService.processDueReminders();
        Reminder r2 = reminderRepository.findById(rem.getId()).orElseThrow();
        assertThat(r2.getAttempts()).isEqualTo(2);
        assertThat(r2.getStatus()).isEqualTo(ReminderStatus.PENDING);

        // Run 3: attempt 3 -> FAILED
        reminderSchedulerService.processDueReminders();
        Reminder r3 = reminderRepository.findById(rem.getId()).orElseThrow();
        assertThat(r3.getAttempts()).isEqualTo(3);
        assertThat(r3.getStatus()).isEqualTo(ReminderStatus.FAILED);
    }

    @Test
    @DisplayName("Reminders: reminder whose deal was cancelled or paid since creation is marked CANCELLED and not sent")
    void testReminderCancelledIfDealNoLongerDepositPending() throws Exception {
        UUID dealId = createDealWithDueDate(tokenA, "canceltest@cust.com", Instant.now().plusSeconds(86400 * 5));
        Reminder rem = reminderRepository.findByDealId(dealId).get(0);
        rem.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(rem);

        // Deal is cancelled
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancel deal"))))
                .andExpect(status().isOk());

        fakeMailSender.reset();
        reminderSchedulerService.processDueReminders();

        assertThat(fakeMailSender.getSentMails()).isEmpty();
        Reminder updated = reminderRepository.findById(rem.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Manual reminder endpoint: 202 while scheduled is pending, 409 for second manual, 10-thread race, 404 for other seller, 202 allowed after sent")
    void testManualReminderLifecycleAndConcurrency() throws Exception {
        // 1. Create deal with dueDate -> automatic SCHEDULED reminder is created and is PENDING
        UUID dealId = createDealWithDueDate(tokenA, "cust_manual@test.com", Instant.now().plusSeconds(86400 * 5));
        List<Reminder> initialRems = reminderRepository.findByDealId(dealId);
        assertThat(initialRems).hasSize(1);
        assertThat(initialRems.get(0).getKind()).isEqualTo(com.vaultri.core.reminder.entity.ReminderKind.SCHEDULED);
        assertThat(initialRems.get(0).getStatus()).isEqualTo(ReminderStatus.PENDING);

        // 2. Manual reminder succeeds (202) while automatic scheduled one is PENDING
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isAccepted());

        List<Reminder> remsAfterFirstManual = reminderRepository.findByDealId(dealId);
        assertThat(remsAfterFirstManual).hasSize(2);
        long pendingCount = remsAfterFirstManual.stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING).count();
        assertThat(pendingCount).isEqualTo(2);

        // 3. Second manual reminder while one is pending returns 409
        MvcResult conflictRes = mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict())
                .andReturn();
        String conflictBody = conflictRes.getResponse().getContentAsString();
        assertThat(conflictBody).doesNotContain("idx_reminders_pending_manual");
        assertThat(conflictBody).doesNotContain("reminders (");

        // 4. Other seller requests manual reminder -> 404 (Deal Not Found)
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // 5. Mark the pending manual reminder as SENT, then a new manual reminder is allowed (202)
        Reminder manualRem = remsAfterFirstManual.stream()
                .filter(r -> r.getKind() == com.vaultri.core.reminder.entity.ReminderKind.MANUAL)
                .findFirst().orElseThrow();
        manualRem.setStatus(ReminderStatus.SENT);
        reminderRepository.save(manualRem);

        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isAccepted());

        // 6. Concurrency test: 10 threads post manual reminder at once on a new deal -> exactly one 202, nine 409s, no 500s
        UUID concurrentDealId = createDealWithDueDate(tokenA, "cust_concurrent_man@test.com", Instant.now().plusSeconds(86400 * 5));
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return mockMvc.perform(post("/api/v1/deals/" + concurrentDealId + "/reminders")
                                .header("Authorization", "Bearer " + tokenA))
                        .andReturn().getResponse().getStatus();
            }));
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int count202 = 0;
        int count409 = 0;
        int countOther = 0;
        for (var f : futures) {
            int st = f.get();
            if (st == 202) count202++;
            else if (st == 409) count409++;
            else countOther++;
        }

        assertThat(count202).isEqualTo(1);
        assertThat(count409).isEqualTo(9);
        assertThat(countOther).isEqualTo(0);
    }

    @Test
    @DisplayName("Layer A (Row Lock): direct concurrent claimAndDispatchOneReminder sends all 20 due reminders exactly once")
    void testLayerARowLockConcurrency() throws Exception {
        fakeMailSender.reset();
        // Create 20 due reminders across 20 deals
        for (int i = 0; i < 20; i++) {
            UUID dId = createDealWithDueDate(tokenA, "user" + i + "@rowlock.com", Instant.now().plusSeconds(86400 * 5));
            Reminder r = reminderRepository.findByDealId(dId).get(0);
            r.setDueAt(Instant.now().minusSeconds(100 + i));
            reminderRepository.save(r);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable worker = () -> {
            try {
                latch.await();
                while (reminderDispatchService.claimAndDispatchOneReminder()) {
                    // keep claiming until no more due reminders
                }
            } catch (Exception ignored) {}
        };

        executor.submit(worker);
        executor.submit(worker);

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Assert all 20 were processed and sent exactly once
        assertThat(fakeMailSender.getSentMails()).hasSize(20);
        long sentCountInDb = reminderRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReminderStatus.SENT)
                .count();
        assertThat(sentCountInDb).isEqualTo(20);
    }

    @Test
    @DisplayName("Layer B (ShedLock): concurrent scheduler entry points allow only one run to do work")
    void testLayerBShedLockConcurrency() throws Exception {
        fakeMailSender.reset();
        UUID dId = createDealWithDueDate(tokenA, "user_shedlock@test.com", Instant.now().plusSeconds(86400 * 5));
        Reminder r = reminderRepository.findByDealId(dId).get(0);
        r.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(r);

        // Pre-acquire the ShedLock lock so that when ReminderSchedulerService runs with @SchedulerLock,
        // it cannot acquire the lock and will do nothing.
        net.javacrumbs.shedlock.core.LockConfiguration lockConfig = new net.javacrumbs.shedlock.core.LockConfiguration(
                Instant.now(),
                "reminderSchedulerJob",
                java.time.Duration.ofMinutes(5),
                java.time.Duration.ZERO
        );
        Optional<net.javacrumbs.shedlock.core.SimpleLock> lockOpt = lockProvider.lock(lockConfig);
        assertThat(lockOpt).isPresent();

        try {
            // Execute the scheduled entry point while the lock is held
            reminderSchedulerService.processDueReminders();

            // When @SchedulerLock is present, the job is prevented from running because the lock is held.
            // When @SchedulerLock is mutated/removed, the job runs anyway and sends the email.
            assertThat(fakeMailSender.getSentMails()).isEmpty();
            Reminder unupdated = reminderRepository.findById(r.getId()).orElseThrow();
            assertThat(unupdated.getStatus()).isEqualTo(ReminderStatus.PENDING);
        } finally {
            lockOpt.get().unlock();
        }
    }
}
