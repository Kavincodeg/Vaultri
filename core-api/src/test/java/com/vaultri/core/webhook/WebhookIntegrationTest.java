package com.vaultri.core.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.audit.entity.AuditLog;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.auth.service.AuthService;
import com.vaultri.core.deal.dto.CreateDealCommand;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.service.DealService;
import com.vaultri.core.ledger.entity.LedgerEntry;
import com.vaultri.core.ledger.repository.LedgerRepository;
import com.vaultri.core.payment.config.RazorpayProperties;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.webhook.entity.WebhookEvent;
import com.vaultri.core.webhook.repository.WebhookRepository;
import com.vaultri.core.webhook.service.InternalSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RazorpayProperties razorpayProperties;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private DealService dealService;

    @SpyBean
    private InternalSettlementService internalSettlementService;

    private Seller seller;

    @BeforeEach
    void setUp() {
        String email = "webhook_seller_" + UUID.randomUUID() + "@vaultri.com";
        authService.signup(new SignupRequest(email, "Password123!", "Webhook Seller", null));
        seller = sellerRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private String calculateSignature(byte[] bodyBytes, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            return HexFormat.of().formatHex(mac.doFinal(bodyBytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Deal createPendingDealWithPayment(String linkId, long amount) {
        CreateDealCommand cmd = CreateDealCommand.builder()
                .customerName("Client Corp")
                .customerEmail("client@corp.com")
                .title("Deal Title")
                .price(amount * 5)
                .depositPercent(20)
                .build();
        Deal draft = dealService.createDraft(seller.getId(), cmd);
        Deal pending = dealService.markDepositPending(draft.getId(), seller.getId());

        Payment payment = Payment.builder()
                .deal(pending)
                .amount(amount)
                .type(PaymentType.DEPOSIT)
                .status(PaymentStatus.CREATED)
                .razorpayLinkId(linkId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        paymentRepository.save(payment);
        return pending;
    }

    private String createPaymentLinkPaidPayload(String linkId, long amount, String paymentId) {
        return """
                {
                  "event": "payment_link.paid",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "%s",
                        "amount": %d,
                        "amount_paid": %d,
                        "status": "paid"
                      }
                    },
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "amount": %d,
                        "status": "captured"
                      }
                    }
                  }
                }
                """.formatted(linkId, amount, amount, paymentId, amount);
    }

    @Test
    @DisplayName("Webhook Security: Missing signature header returns 401 and writes nothing")
    void testMissingSignatureHeaderReturns401() throws Exception {
        String eventId = "evt_miss_sig_" + UUID.randomUUID();
        byte[] body = "{\"event\":\"payment_link.paid\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(webhookRepository.findByRazorpayEventId(eventId)).isEmpty();
    }

    @Test
    @DisplayName("Webhook Security: Invalid signature returns 401 with no webhook_events row")
    void testInvalidSignatureReturns401() throws Exception {
        String eventId = "evt_inv_sig_" + UUID.randomUUID();
        byte[] body = "{\"event\":\"payment_link.paid\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", "wrong_signature_hex_value")
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(webhookRepository.findByRazorpayEventId(eventId)).isEmpty();
    }

    @Test
    @DisplayName("Webhook Security: Body altered after signing returns 401 and records nothing")
    void testAlteredBodyReturns401() throws Exception {
        String eventId = "evt_alt_" + UUID.randomUUID();
        String original = "{\"event\":\"payment_link.paid\"}";
        String sig = calculateSignature(original.getBytes(StandardCharsets.UTF_8), razorpayProperties.getWebhookSecret());

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content((original + " ").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());

        assertThat(webhookRepository.findByRazorpayEventId(eventId)).isEmpty();
    }

    @Test
    @DisplayName("Webhook Security: Blank webhook secret rejects every request even with correctly computed signature")
    void testBlankWebhookSecretRejects() throws Exception {
        String originalSecret = razorpayProperties.getWebhookSecret();
        try {
            razorpayProperties.setWebhookSecret("");
            String eventId = "evt_blank_sec_" + UUID.randomUUID();
            byte[] body = "{\"event\":\"payment_link.paid\"}".getBytes(StandardCharsets.UTF_8);
            String sig = calculateSignature(body, "any_secret");

            mockMvc.perform(post("/webhooks/razorpay")
                            .header("X-Razorpay-Signature", sig)
                            .header("X-Razorpay-Event-Id", eventId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());

            assertThat(webhookRepository.findByRazorpayEventId(eventId)).isEmpty();
        } finally {
            razorpayProperties.setWebhookSecret(originalSecret);
        }
    }

    @Test
    @DisplayName("Webhook Security: Body over 256 KB is rejected")
    void testPayloadOver256KbRejected() throws Exception {
        byte[] oversized = new byte[256 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'a');
        String sig = calculateSignature(oversized, razorpayProperties.getWebhookSecret());

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", "evt_large_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("Webhook Security: Missing X-Razorpay-Event-Id returns 400")
    void testMissingEventIdReturns400() throws Exception {
        byte[] body = "{\"event\":\"payment_link.paid\"}".getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Webhook Security: Malformed JSON with valid signature returns 400 and records nothing")
    void testMalformedJsonReturns400() throws Exception {
        String eventId = "evt_bad_json_" + UUID.randomUUID();
        byte[] badJson = "{not-a-valid-json".getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(badJson, razorpayProperties.getWebhookSecret());

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());

        assertThat(webhookRepository.findByRazorpayEventId(eventId)).isEmpty();
    }

    @Test
    @DisplayName("Webhook Behavior: paid event sets payment PAID with razorpay_payment_id, deal DEPOSIT_PAID, 1 ledger entry, 1 audit row, processed_at set")
    void testWebhookPaymentPaidHappyPath() throws Exception {
        String linkId = "plink_hp_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 25000L);
        String payload = createPaymentLinkPaidPayload(linkId, 25000L, "pay_hp_456");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
        String eventId = "evt_hp_" + UUID.randomUUID();

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Payment status -> PAID
        Payment payment = paymentRepository.findByRazorpayLinkId(linkId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getRazorpayPaymentId()).isEqualTo("pay_hp_456");

        // Deal status -> DEPOSIT_PAID
        Deal updatedDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(updatedDeal.getStatus()).isEqualTo(DealStatus.DEPOSIT_PAID);

        // Ledger entry recorded
        List<LedgerEntry> entries = ledgerRepository.findByDealId(deal.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAmount()).isEqualTo(25000L);
        assertThat(entries.get(0).getType()).isEqualTo(PaymentType.DEPOSIT);

        // Audit log recorded
        List<AuditLog> auditLogs = auditRepository.findByDealId(deal.getId());
        assertThat(auditLogs).anyMatch(l -> l.getAction().equals("DEPOSIT_PAID"));

        // Webhook event marked processed
        WebhookEvent event = webhookRepository.findByRazorpayEventId(eventId).orElseThrow();
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("Webhook Behavior: Same event delivered 5 times sequentially gives one ledger entry")
    void testWebhookSequentialRedelivery() throws Exception {
        String linkId = "plink_redeliv_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 30000L);
        String payload = createPaymentLinkPaidPayload(linkId, 30000L, "pay_redeliv_789");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
        String eventId = "evt_redeliv_" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/webhooks/razorpay")
                            .header("X-Razorpay-Signature", sig)
                            .header("X-Razorpay-Event-Id", eventId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        List<LedgerEntry> entries = ledgerRepository.findByDealId(deal.getId());
        assertThat(entries).hasSize(1);
    }

    @Test
    @DisplayName("Webhook Behavior: 50 threads with same event ID give exactly 1 state change, 1 ledger entry, 1 audit row, all 200s")
    void testWebhookConcurrentSameEventId() throws Exception {
        String linkId = "plink_conc_same_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 40000L);
        String payload = createPaymentLinkPaidPayload(linkId, 40000L, "pay_conc_101");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
        String eventId = "evt_conc_same_" + UUID.randomUUID();

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/webhooks/razorpay")
                                .header("X-Razorpay-Signature", sig)
                                .header("X-Razorpay-Event-Id", eventId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Integer> f : futures) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        executor.shutdown();

        List<LedgerEntry> entries = ledgerRepository.findByDealId(deal.getId());
        assertThat(entries).hasSize(1);
        Payment payment = paymentRepository.findByRazorpayLinkId(linkId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        List<AuditLog> auditLogs = auditRepository.findByDealId(deal.getId());
        assertThat(auditLogs.stream().filter(l -> l.getAction().equals("DEPOSIT_PAID")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Webhook Behavior: 10 threads delivering same payment under DIFFERENT event IDs produce exactly 1 ledger entry")
    void testWebhookConcurrentDifferentEventIdsSamePayment() throws Exception {
        String linkId = "plink_conc_diff_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 50000L);
        String payload = createPaymentLinkPaidPayload(linkId, 50000L, "pay_diff_202");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            String eventId = "evt_diff_" + i + "_" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/webhooks/razorpay")
                                .header("X-Razorpay-Signature", sig)
                                .header("X-Razorpay-Event-Id", eventId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Integer> f : futures) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        executor.shutdown();

        List<LedgerEntry> entries = ledgerRepository.findByDealId(deal.getId());
        assertThat(entries).hasSize(1);
    }

    @Test
    @DisplayName("Webhook Behavior: 20 rounds of cancel-versus-paid-webhook race with invariant checks")
    void testWebhookRaceWithCancellation() throws Exception {
        int cancelLostCount = 0;
        for (int round = 0; round < 20; round++) {
            String linkId = "plink_race_" + round + "_" + UUID.randomUUID();
            Deal deal = createPendingDealWithPayment(linkId, 15000L);
            String payload = createPaymentLinkPaidPayload(linkId, 15000L, "pay_race_" + round);
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
            String eventId = "evt_race_" + round + "_" + UUID.randomUUID();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<Integer> cancelFuture = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                try {
                    dealService.cancel(deal.getId(), seller.getId(), 0L);
                    return 200;
                } catch (Exception e) {
                    return 409;
                }
            });

            Future<Integer> webhookFuture = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/webhooks/razorpay")
                                .header("X-Razorpay-Signature", sig)
                                .header("X-Razorpay-Event-Id", eventId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            });

            readyLatch.await(3, TimeUnit.SECONDS);
            startLatch.countDown();

            int cancelStatus = cancelFuture.get(5, TimeUnit.SECONDS);
            if (cancelStatus == 409) {
                cancelLostCount++;
            }
            assertThat(webhookFuture.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            executor.shutdown();

            Deal finalDeal = dealRepository.findById(deal.getId()).orElseThrow();
            assertThat(finalDeal.getStatus()).isIn(DealStatus.CANCELLED, DealStatus.DEPOSIT_PAID);

            List<LedgerEntry> entries = ledgerRepository.findByDealId(deal.getId());
            assertThat(entries.size()).isLessThanOrEqualTo(1);
        }
        System.out.println("Webhook vs cancel race: cancellations that lost the race = " + cancelLostCount + " / 20");
    }

    @Test
    @DisplayName("Webhook Behavior: PAYMENT_RECEIVED_AFTER_CANCELLATION audit row written when deal was already cancelled")
    void testPaymentReceivedAfterCancellation() throws Exception {
        String linkId = "plink_after_cancel_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 20000L);
        // Explicitly cancel the deal first
        dealService.cancel(deal.getId(), seller.getId(), 0L);
        Deal cancelledDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(cancelledDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        // Webhook arrives for the payment
        String payload = createPaymentLinkPaidPayload(linkId, 20000L, "pay_late_123");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
        String eventId = "evt_late_" + UUID.randomUUID();

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Status remains CANCELLED
        Deal postDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(postDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        // Audit log records PAYMENT_RECEIVED_AFTER_CANCELLATION
        List<AuditLog> auditLogs = auditRepository.findByDealId(deal.getId());
        assertThat(auditLogs).anyMatch(l -> l.getAction().equals("PAYMENT_RECEIVED_AFTER_CANCELLATION"));
    }

    @Test
    @DisplayName("Webhook Behavior: amount mismatch leaves payment CREATED with audit row and no ledger entry")
    void testWebhookAmountMismatch() throws Exception {
        String linkId = "plink_mismatch_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 50000L);
        String payload = createPaymentLinkPaidPayload(linkId, 40000L, "pay_bad_amt");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
        String eventId = "evt_mismatch_" + UUID.randomUUID();

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Payment payment = paymentRepository.findByRazorpayLinkId(linkId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        Deal checkDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(checkDeal.getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);

        List<LedgerEntry> entries = ledgerRepository.findByDealId(deal.getId());
        assertThat(entries).isEmpty();

        List<AuditLog> auditLogs = auditRepository.findByDealId(deal.getId());
        assertThat(auditLogs).anyMatch(l -> l.getAction().equals("PAYMENT_AMOUNT_MISMATCH"));
    }

    @Test
    @DisplayName("Webhook Behavior: expired and cancelled events set payment status and leave deal unchanged")
    void testWebhookExpiredAndCancelledEvents() throws Exception {
        // Expired event
        String expiredLinkId = "plink_exp_" + UUID.randomUUID();
        Deal deal1 = createPendingDealWithPayment(expiredLinkId, 20000L);
        String expiredPayload = """
                {
                  "event": "payment_link.expired",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "%s"
                      }
                    }
                  }
                }
                """.formatted(expiredLinkId);
        byte[] expBody = expiredPayload.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", calculateSignature(expBody, razorpayProperties.getWebhookSecret()))
                        .header("X-Razorpay-Event-Id", "evt_exp_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expBody))
                .andExpect(status().isOk());

        Payment expPayment = paymentRepository.findByRazorpayLinkId(expiredLinkId).orElseThrow();
        assertThat(expPayment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(dealRepository.findById(deal1.getId()).orElseThrow().getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);

        // Cancelled event
        String cancelledLinkId = "plink_can_" + UUID.randomUUID();
        Deal deal2 = createPendingDealWithPayment(cancelledLinkId, 20000L);
        String cancelledPayload = """
                {
                  "event": "payment_link.cancelled",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "%s"
                      }
                    }
                  }
                }
                """.formatted(cancelledLinkId);
        byte[] canBody = cancelledPayload.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", calculateSignature(canBody, razorpayProperties.getWebhookSecret()))
                        .header("X-Razorpay-Event-Id", "evt_can_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canBody))
                .andExpect(status().isOk());

        Payment canPayment = paymentRepository.findByRazorpayLinkId(cancelledLinkId).orElseThrow();
        assertThat(canPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(dealRepository.findById(deal2.getId()).orElseThrow().getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);
    }

    @Test
    @DisplayName("Webhook Behavior: unknown event type and unknown link id return 200 and change nothing")
    void testUnknownEventAndUnknownLink() throws Exception {
        // Unknown link ID returns 200
        String unknownLinkPayload = """
                {
                  "event": "payment_link.paid",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "plink_unknown_999",
                        "amount": 1000,
                        "amount_paid": 1000
                      }
                    }
                  }
                }
                """;
        byte[] unkBody = unknownLinkPayload.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", calculateSignature(unkBody, razorpayProperties.getWebhookSecret()))
                        .header("X-Razorpay-Event-Id", "evt_unk_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unkBody))
                .andExpect(status().isOk());

        // Unknown event type returns 200
        String unknownEventPayload = """
                {
                  "event": "payment_link.updated_custom",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "plink_random"
                      }
                    }
                  }
                }
                """;
        byte[] unkEvtBody = unknownEventPayload.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", calculateSignature(unkEvtBody, razorpayProperties.getWebhookSecret()))
                        .header("X-Razorpay-Event-Id", "evt_unk_type_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unkEvtBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Webhook Rollback: throwing after webhook_events insert causes 500, rollback, and successful redelivery")
    void testWebhookRollbackOnFailure() throws Exception {
        String linkId = "plink_rb_" + UUID.randomUUID();
        Deal deal = createPendingDealWithPayment(linkId, 35000L);
        String payload = createPaymentLinkPaidPayload(linkId, 35000L, "pay_rb_123");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String sig = calculateSignature(body, razorpayProperties.getWebhookSecret());
        String eventId = "evt_rb_" + UUID.randomUUID();

        // Configure spy to throw runtime exception on first execution attempt
        doThrow(new RuntimeException("Simulated unexpected failure inside transaction"))
                .doCallRealMethod()
                .when(internalSettlementService).processWebhookEvent(any(), any());

        // Attempt 1: Should fail with 500
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError());

        // Ensure database transaction rolled back: no webhook_events row, payment still CREATED
        assertThat(webhookRepository.findByRazorpayEventId(eventId)).isEmpty();
        Payment paymentBefore = paymentRepository.findByRazorpayLinkId(linkId).orElseThrow();
        assertThat(paymentBefore.getStatus()).isEqualTo(PaymentStatus.CREATED);

        // Attempt 2: Redelivered event succeeds with 200
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Payment paymentAfter = paymentRepository.findByRazorpayLinkId(linkId).orElseThrow();
        assertThat(paymentAfter.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(dealRepository.findById(deal.getId()).orElseThrow().getStatus()).isEqualTo(DealStatus.DEPOSIT_PAID);
        assertThat(ledgerRepository.findByDealId(deal.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Payload size limit: Content-Length > 256 KB returns 413 Payload Too Large before reading")
    void testOversizedContentLengthReturns413() throws Exception {
        byte[] oversizedBody = new byte[256 * 1024 + 10]; // 256 KB + 10 bytes
        Arrays.fill(oversizedBody, (byte) ' ');

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", "any_sig")
                        .header("X-Razorpay-Event-Id", "evt_oversized_" + UUID.randomUUID())
                        .header("Content-Length", String.valueOf(oversizedBody.length))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("Payload size limit: Chunked/stream body > 256 KB returns 413 Payload Too Large")
    void testOversizedStreamWithoutContentLengthReturns413() throws Exception {
        byte[] oversizedBody = new byte[256 * 1024 + 10];
        Arrays.fill(oversizedBody, (byte) ' ');

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", "any_sig")
                        .header("X-Razorpay-Event-Id", "evt_chunked_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("Payload size limit: Exactly 256 KB (262,144 bytes) is accepted up to signature check")
    void testExact256KBBodyIsAccepted() throws Exception {
        // Build a valid JSON payload padded with spaces up to exactly 256 KB (262,144 bytes)
        String prefix = "{\"event\":\"payment_link.unknown\",\"data\":{\"pad\":\"";
        String suffix = "\"}}";
        int padLength = (256 * 1024) - prefix.length() - suffix.length();
        byte[] padBytes = new byte[padLength];
        Arrays.fill(padBytes, (byte) 'a');
        byte[] exact256KBBody = (prefix + new String(padBytes, StandardCharsets.UTF_8) + suffix).getBytes(StandardCharsets.UTF_8);
        assertThat(exact256KBBody.length).isEqualTo(256 * 1024);

        // Signature with valid HMAC over exact 256 KB payload
        String sig = calculateSignature(exact256KBBody, razorpayProperties.getWebhookSecret());

        // Should pass 256 KB size limit and signature verification, then return 200 OK (unknown event)
        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", "evt_exact256_" + UUID.randomUUID())
                        .header("Content-Length", String.valueOf(exact256KBBody.length))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exact256KBBody))
                .andExpect(status().isOk());
    }
}
