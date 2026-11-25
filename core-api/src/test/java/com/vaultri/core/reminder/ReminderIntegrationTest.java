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
    private DealRepository dealRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private FakeMailSender fakeMailSender;

    @Autowired
    private ReminderSchedulerService reminderSchedulerService;

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
    @DisplayName("Manual reminder endpoint: 202 for owner, 409 for second pending request, 404 for another seller's deal")
    void testManualReminderEndpoint() throws Exception {
        CreateDealRequest req = new CreateDealRequest(
                "Cust", "cust@manual.com", null, "Manual Deal", 100000L, 20, null, null
        );
        String dealJson = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID dealId = UUID.fromString(objectMapper.readTree(dealJson).path("id").asText());

        // 1. Owner requests manual reminder -> 202
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isAccepted());

        List<Reminder> rems = reminderRepository.findByDealId(dealId);
        assertThat(rems).hasSize(1);
        assertThat(rems.get(0).getStatus()).isEqualTo(ReminderStatus.PENDING);

        // 2. Second request while one is pending -> 409 Conflict
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict());

        // 3. Another seller requests reminder on this deal -> 404
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/reminders")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
