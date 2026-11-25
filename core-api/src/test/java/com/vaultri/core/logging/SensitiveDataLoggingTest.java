package com.vaultri.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.payment.config.RazorpayProperties;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SensitiveDataLoggingTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RazorpayProperties razorpayProperties;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && listAppender != null) {
            rootLogger.detachAppender(listAppender);
        }
    }

    private String getCapturedLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : listAppender.list) {
            sb.append(event.getFormattedMessage()).append("\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Log Capture: Concurrent signup race logs contain no email addresses or phone numbers")
    void testConcurrentSignupLogsNoPii() throws Exception {
        String testEmail = "sensitive_signup_" + UUID.randomUUID() + "@secretvault.com";
        String testPhone = "+919876543210";
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new SignupRequest(testEmail, "Password123!", "Seller", testPhone))));
                } catch (Exception ignored) {}
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        String logs = getCapturedLogs();
        assertThat(logs).doesNotContain(testEmail);
        assertThat(logs).doesNotContain(testPhone);
    }

    @Test
    @DisplayName("Log Capture: Deal creation logs contain no email addresses or phone numbers")
    void testDealCreationLogsNoPii() throws Exception {
        String sellerEmail = "seller_log_deal_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(sellerEmail, "Password123!", "Seller", "+919111111111"))));

        String token = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(sellerEmail, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();

        listAppender.list.clear();

        String custEmail = "cust_deal_" + UUID.randomUUID() + "@customer.com";
        String custPhone = "+919222222222";
        CreateDealRequest request = new CreateDealRequest(
                "Customer Name", custEmail, custPhone, "Project Title", 100000L, 20, Instant.now().plusSeconds(86400), "Contract terms"
        );

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        String logs = getCapturedLogs();
        assertThat(logs).doesNotContain(custEmail);
        assertThat(logs).doesNotContain(custPhone);
    }

    @Test
    @DisplayName("Log Capture: Webhook delivery logs contain no email addresses or phone numbers")
    void testWebhookLogsNoPii() throws Exception {
        listAppender.list.clear();
        String secretCustEmail = "secret_webhook_" + UUID.randomUUID() + "@webhookcust.com";
        String payload = String.format("""
                {
                  "event": "payment_link.paid",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "plink_unregistered_%s",
                        "customer": {
                          "email": "%s",
                          "contact": "+919333333333"
                        }
                      }
                    }
                  }
                }
                """, UUID.randomUUID(), secretCustEmail);

        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(body));

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", "evt_pii_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String logs = getCapturedLogs();
        assertThat(logs).doesNotContain(secretCustEmail);
        assertThat(logs).doesNotContain("+919333333333");
    }

    @Test
    @DisplayName("Log Capture: Cancellation logs contain no email addresses or phone numbers")
    void testCancellationLogsNoPii() throws Exception {
        String sellerEmail = "seller_cancel_pii_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(sellerEmail, "Password123!", "Seller", null))));

        String token = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(sellerEmail, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();

        String custEmail = "cust_cancel_" + UUID.randomUUID() + "@custpii.com";
        String createDealRes = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDealRequest(
                                "Cust", custEmail, "+919444444444", "Cancel Project", 100000L, 20, null, null
                        ))))
                .andReturn().getResponse().getContentAsString();
        String dealId = objectMapper.readTree(createDealRes).path("id").asText();

        listAppender.list.clear();

        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Free text secret customer reason"))))
                .andExpect(status().isOk());

        String logs = getCapturedLogs();
        assertThat(logs).doesNotContain(custEmail);
        assertThat(logs).doesNotContain("+919444444444");
        assertThat(logs).doesNotContain("Free text secret customer reason");
    }

    @Autowired
    private com.vaultri.core.mail.service.FakeMailSender fakeMailSender;

    @Autowired
    private com.vaultri.core.reminder.service.ReminderDispatchService reminderDispatchService;

    @Autowired
    private com.vaultri.core.reminder.service.ReminderSchedulerService reminderSchedulerService;

    @Autowired
    private com.vaultri.core.reminder.repository.ReminderRepository reminderRepository;

    @Test
    @DisplayName("Log Capture: Mail failures with recipient in exception message or cause do not leak recipient address in logs")
    void testMailFailureDoesNotLeakRecipientInLogs() throws Exception {
        String sellerEmail = "seller_mail_leak_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(sellerEmail, "Password123!", "Seller", null))));

        String token = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(sellerEmail, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();

        String secretRecipient = "cust_leak_" + UUID.randomUUID() + "@leaksensitivetest.com";
        String createDealRes = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDealRequest(
                                "Cust", secretRecipient, null, "Mail Leak Test", 100000L, 20, Instant.now().plusSeconds(86400 * 5), null
                        ))))
                .andReturn().getResponse().getContentAsString();
        UUID dealId = UUID.fromString(objectMapper.readTree(createDealRes).path("id").asText());

        var rem = reminderRepository.findByDealId(dealId).get(0);
        rem.setDueAt(Instant.now().minusSeconds(60));
        reminderRepository.save(rem);

        // Configure FakeMailSender to throw an exception whose message and nested cause contain secretRecipient
        Throwable cause = new IllegalArgumentException("Nested SMTP cause containing " + secretRecipient);
        fakeMailSender.setCustomExceptionToThrow(new RuntimeException("Mail delivery failed to " + secretRecipient, cause));

        listAppender.list.clear();

        // 1. Run direct dispatch
        reminderDispatchService.claimAndDispatchOneReminder();

        // 2. Run scheduled entry point
        reminderSchedulerService.processDueReminders();

        fakeMailSender.reset();

        String capturedLogs = getCapturedLogs();
        assertThat(capturedLogs).doesNotContain(secretRecipient);
    }
}
