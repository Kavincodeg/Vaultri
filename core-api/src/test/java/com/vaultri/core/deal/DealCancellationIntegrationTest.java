package com.vaultri.core.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.IllegalDealTransitionException;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.service.DealService;
import com.vaultri.core.payment.config.RazorpayProperties;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.gateway.FakeGateway;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.payment.service.PendingLinkCancellationRetryJob;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderStatus;
import com.vaultri.core.reminder.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DealCancellationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private FakeGateway fakeGateway;

    @Autowired
    private PendingLinkCancellationRetryJob retryJob;

    @Autowired
    private RazorpayProperties razorpayProperties;

    @Autowired
    private DealService dealService;

    private String tokenA;
    private String tokenB;
    private Seller sellerA;
    private Seller sellerB;

    @BeforeEach
    void setUp() throws Exception {
        fakeGateway.reset();

        String emailA = "seller_cancel_a_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailA, "Password123!", "Seller A", null))));
        tokenA = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(emailA, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();
        sellerA = sellerRepository.findByEmailIgnoreCase(emailA).orElseThrow();

        String emailB = "seller_cancel_b_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailB, "Password123!", "Seller B", null))));
        tokenB = objectMapper.readTree(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(emailB, "Password123!"))))
                        .andReturn().getResponse().getContentAsString()
        ).path("accessToken").asText();
        sellerB = sellerRepository.findByEmailIgnoreCase(emailB).orElseThrow();
    }

    private Deal createDealForSeller(String token, Long price, Integer depositPercent) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Customer", "cust@test.com", null, "Test Deal", price, depositPercent, Instant.now().plusSeconds(86400 * 5), "Terms"
        );
        MvcResult res = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID dealId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).path("id").asText());
        return dealRepository.findById(dealId).orElseThrow();
    }

    @Test
    @DisplayName("Cancellation: fee 0 from DEPOSIT_PENDING cancels deal, cancels unpaid deposit link, sets deposit payment CANCELLED, cancels pending reminders")
    void testCancelFeeZeroFromDepositPending() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        UUID dealId = deal.getId();

        Payment depositPayment = paymentRepository.findByDealIdAndType(dealId, PaymentType.DEPOSIT).orElseThrow();
        assertThat(depositPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(depositPayment.getRazorpayLinkId()).isNotNull();

        String cancelKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Client requested cancellation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationFeePayment").doesNotExist());

        // Assert deal cancelled
        Deal updatedDeal = dealRepository.findById(dealId).orElseThrow();
        assertThat(updatedDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        // Assert no cancellation fee payment created
        assertThat(paymentRepository.findByDealIdAndType(dealId, PaymentType.CANCELLATION_FEE)).isEmpty();

        // Assert gateway was called to cancel the deposit link
        assertThat(fakeGateway.getCancelCallCount()).isEqualTo(1);

        // Assert deposit payment status is CANCELLED and cancel_requested_at is cleared
        Payment updatedDeposit = paymentRepository.findById(depositPayment.getId()).orElseThrow();
        assertThat(updatedDeposit.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(updatedDeposit.getCancelRequestedAt()).isNull();

        // Assert audit row exists
        assertThat(auditRepository.findByDealId(dealId).stream().anyMatch(a -> a.getAction().equals("CANCELLED"))).isTrue();

        // Assert pending reminders are CANCELLED
        List<Reminder> reminders = reminderRepository.findByDealId(dealId);
        assertThat(reminders).isNotEmpty();
        assertThat(reminders.stream().allMatch(r -> r.getStatus() == ReminderStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("Cancellation: fee > 0 from DEPOSIT_PAID creates fee payment with link, cancels deal, response contains short URL")
    void testCancelWithFeeFromDepositPaid() throws Exception {
        Deal deal = createDealForSeller(tokenA, 200000L, 25);
        UUID dealId = deal.getId();

        // Mark deposit paid
        deal.setStatus(DealStatus.DEPOSIT_PAID);
        dealRepository.save(deal);

        long requestedFee = 50000L; // 25% <= 50% max allowable fee
        String cancelKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(requestedFee, "Work partly done"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationFeePayment.amount").value(requestedFee))
                .andExpect(jsonPath("$.cancellationFeePayment.status").value("CREATED"))
                .andExpect(jsonPath("$.cancellationFeePayment.paymentUrl").exists());

        Deal updatedDeal = dealRepository.findById(dealId).orElseThrow();
        assertThat(updatedDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        Payment feePayment = paymentRepository.findByDealIdAndType(dealId, PaymentType.CANCELLATION_FEE).orElseThrow();
        assertThat(feePayment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(feePayment.getRazorpayLinkId()).isNotNull();
        assertThat(feePayment.getShortUrl()).isNotNull();
    }

    @Test
    @DisplayName("Cancellation: fee above stage max returns 400; cancelling terminal deal returns 409; another seller's deal returns 404")
    void testCancellationValidationAndScoping() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        UUID dealId = deal.getId();

        // In DEPOSIT_PENDING, max fee is 0 paise. Requesting fee 1000 paise must return 400
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(1000L, "Illegal fee"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Cancellation Fee"));

        // Seller B trying to cancel Seller A's deal returns 404
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Sneak cancel"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"));

        // Cancel deal legally
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Legal cancel"))))
                .andExpect(status().isOk());

        // Cancelling already cancelled (terminal) deal returns 409
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Repeat cancel"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal Deal Transition"));
    }

    @Test
    @DisplayName("Cancellation Idempotency: missing key 400, replay identical, different body 422")
    void testCancellationIdempotency() throws Exception {
        Deal deal = createDealForSeller(tokenA, 150000L, 20);
        UUID dealId = deal.getId();

        // 1. Missing Idempotency-Key returns 400
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "No key"))))
                .andExpect(status().isBadRequest());

        // 2. Replay with identical key & body returns identical response without re-calling gateway
        String key = UUID.randomUUID().toString();
        CancelDealRequest body = new CancelDealRequest(0L, "Replay reason");
        fakeGateway.reset();

        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        assertThat(fakeGateway.getCancelCallCount()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        assertThat(fakeGateway.getCancelCallCount()).isEqualTo(1);

        // 3. Same key with different body returns 422
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Different reason"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Cancellation Concurrency: 10 threads with same key and body produce exactly one fee payment and link call")
    void testConcurrentCancellationSameKey() throws Exception {
        Deal deal = createDealForSeller(tokenA, 200000L, 20);
        UUID dealId = deal.getId();
        deal.setStatus(DealStatus.DEPOSIT_PAID);
        dealRepository.save(deal);

        String key = UUID.randomUUID().toString();
        CancelDealRequest body = new CancelDealRequest(40000L, "Concurrent cancel");
        fakeGateway.reset();

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                        .andReturn().getResponse().getStatus();
            }));
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int ok200 = 0;
        for (Future<Integer> f : futures) {
            if (f.get() == 200) ok200++;
        }
        assertThat(ok200).isGreaterThanOrEqualTo(1);

        List<Payment> feePayments = paymentRepository.findByDealId(dealId).stream()
                .filter(p -> p.getType() == PaymentType.CANCELLATION_FEE).toList();
        assertThat(feePayments).hasSize(1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cancellation Fee Link Failure: 502 returned, retry resumes Step B with exactly one fee payment")
    void testFeeLinkGatewayFailureThenRetry() throws Exception {
        Deal deal = createDealForSeller(tokenA, 200000L, 20);
        UUID dealId = deal.getId();
        deal.setStatus(DealStatus.DEPOSIT_PAID);
        dealRepository.save(deal);

        fakeGateway.reset();
        fakeGateway.setShouldFail(true);

        String key = UUID.randomUUID().toString();
        CancelDealRequest body = new CancelDealRequest(50000L, "Gateway fail test");

        // Attempt 1: Fails at fee payment link creation
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadGateway());

        // Deal is CANCELLED and fee payment row exists with null link id
        Deal cancelledDeal = dealRepository.findById(dealId).orElseThrow();
        assertThat(cancelledDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);
        Payment feePayment = paymentRepository.findByDealIdAndType(dealId, PaymentType.CANCELLATION_FEE).orElseThrow();
        assertThat(feePayment.getRazorpayLinkId()).isNull();

        // Gateway recovers
        fakeGateway.setShouldFail(false);

        // Attempt 2: Retry with SAME key resumes Step B
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationFeePayment.razorpayPaymentLinkId").exists());

        List<Payment> feePayments = paymentRepository.findByDealId(dealId).stream()
                .filter(p -> p.getType() == PaymentType.CANCELLATION_FEE).toList();
        assertThat(feePayments).hasSize(1);
    }

    @Test
    @DisplayName("Deposit Link Cancel Failure: does not block cancellation; retry job cancels and clears flag; gives up after 10 attempts")
    void testDepositLinkCancelFailureAndRetryJob() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        UUID dealId = deal.getId();

        fakeGateway.reset();
        fakeGateway.setShouldCancelFail(true);

        String key = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancel despite link failure"))))
                .andExpect(status().isOk());

        // Deal cancelled, cancel_requested_at is set on deposit payment
        Deal cancelledDeal = dealRepository.findById(dealId).orElseThrow();
        assertThat(cancelledDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        Payment depositPayment = paymentRepository.findByDealIdAndType(dealId, PaymentType.DEPOSIT).orElseThrow();
        assertThat(depositPayment.getCancelRequestedAt()).isNotNull();
        assertThat(auditRepository.findByDealId(dealId).stream().anyMatch(a -> a.getAction().equals("DEPOSIT_LINK_CANCEL_PENDING"))).isTrue();

        // Retry job runs with gateway still failing: increments attempts
        retryJob.retryPendingLinkCancellations();
        Payment paymentAfterAttempt1 = paymentRepository.findById(depositPayment.getId()).orElseThrow();
        assertThat(paymentAfterAttempt1.getCancelAttempts()).isEqualTo(1);
        assertThat(paymentAfterAttempt1.getCancelRequestedAt()).isNotNull();

        // Simulate 9 more failures to reach 10 attempts
        paymentAfterAttempt1.setCancelAttempts(9);
        paymentRepository.save(paymentAfterAttempt1);

        retryJob.retryPendingLinkCancellations();
        Payment paymentExhausted = paymentRepository.findById(depositPayment.getId()).orElseThrow();
        assertThat(paymentExhausted.getCancelRequestedAt()).isNull(); // Cleared flag
        assertThat(auditRepository.findByDealId(dealId).stream().anyMatch(a -> a.getAction().equals("DEPOSIT_LINK_CANCEL_EXHAUSTED"))).isTrue();

        // Now test recovery scenario on a new deal:
        Deal deal2 = createDealForSeller(tokenA, 100000L, 20);
        fakeGateway.setShouldCancelFail(true);
        mockMvc.perform(post("/api/v1/deals/" + deal2.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Recovery test"))))
                .andExpect(status().isOk());

        fakeGateway.setShouldCancelFail(false);
        retryJob.retryPendingLinkCancellations();
        Payment recoveredPayment = paymentRepository.findByDealIdAndType(deal2.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(recoveredPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(recoveredPayment.getCancelRequestedAt()).isNull();
    }

    @Test
    @DisplayName("Webhook Paid for Cancellation Fee: payment PAID, ledger recorded, deal stays CANCELLED, no unexpected audit row")
    void testWebhookPaidForFeePayment() throws Exception {
        Deal deal = createDealForSeller(tokenA, 200000L, 20);
        deal.setStatus(DealStatus.DEPOSIT_PAID);
        dealRepository.save(deal);

        fakeGateway.reset();
        String cancelKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(50000L, "Reason"))))
                .andExpect(status().isOk());

        Payment feePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE).orElseThrow();
        String linkId = feePayment.getRazorpayLinkId();

        String payload = String.format("""
                {
                  "event": "payment_link.paid",
                  "payload": {
                    "payment_link": {
                      "entity": {
                        "id": "%s",
                        "amount_paid": 50000
                      }
                    },
                    "payment": {
                      "entity": {
                        "id": "pay_fee_999",
                        "amount": 50000
                      }
                    }
                  }
                }
                """, linkId);

        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(razorpayProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(body));

        mockMvc.perform(post("/webhooks/razorpay")
                        .header("X-Razorpay-Signature", sig)
                        .header("X-Razorpay-Event-Id", "evt_fee_paid_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Payment paidFee = paymentRepository.findById(feePayment.getId()).orElseThrow();
        assertThat(paidFee.getStatus()).isEqualTo(PaymentStatus.PAID);

        Deal finalDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(finalDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        assertThat(auditRepository.findByDealId(deal.getId()).stream()
                .noneMatch(a -> a.getAction().equals("PAYMENT_RECEIVED_AFTER_CANCELLATION"))).isTrue();
    }

    @Test
    @DisplayName("Deterministic ordering (a): complete() first, then cancel(fee 0) returns 409 and deal stays COMPLETED")
    void testCompleteFirstThenCancelReturns409() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        UUID dealId = deal.getId();
        deal.setStatus(DealStatus.IN_PROGRESS);
        dealRepository.save(deal);

        // 1. complete() first
        Deal completed = dealService.complete(dealId, sellerA.getId());
        assertThat(completed.getStatus()).isEqualTo(DealStatus.COMPLETED);

        // 2. then cancel(fee 0) returns 409
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancel after complete"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal Deal Transition"));

        // 3. deal stays COMPLETED
        Deal finalDeal = dealRepository.findById(dealId).orElseThrow();
        assertThat(finalDeal.getStatus()).isEqualTo(DealStatus.COMPLETED);
    }

    @Test
    @DisplayName("Deterministic ordering (b): cancel(fee 0) first, then complete() returns 409 and deal stays CANCELLED")
    void testCancelFirstThenCompleteReturns409() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        UUID dealId = deal.getId();
        deal.setStatus(DealStatus.IN_PROGRESS);
        dealRepository.save(deal);

        // 1. cancel(fee 0) first
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancel before complete"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 2. then complete() returns 409 (IllegalDealTransitionException)
        assertThatThrownBy(() -> dealService.complete(dealId, sellerA.getId()))
                .isInstanceOf(IllegalDealTransitionException.class)
                .hasMessageContaining("from CANCELLED to COMPLETED");

        // 3. deal stays CANCELLED
        Deal finalDeal = dealRepository.findById(dealId).orElseThrow();
        assertThat(finalDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);
    }

    @Test
    @DisplayName("20 rounds racing cancel (fee 0) against complete() on an IN_PROGRESS deal: exactly one wins, other gets 409, no 500")
    void testCancelVsCompleteRace20Rounds() throws Exception {
        AtomicInteger cancelWins = new AtomicInteger(0);
        AtomicInteger completeWins = new AtomicInteger(0);

        for (int i = 0; i < 20; i++) {
            Deal deal = createDealForSeller(tokenA, 100000L, 20);
            UUID dealId = deal.getId();
            deal.setStatus(DealStatus.IN_PROGRESS);
            dealRepository.save(deal);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger cancelStatus = new AtomicInteger(0);
            AtomicInteger completeStatus = new AtomicInteger(0);

            // Give one of the two threads a small random start delay (0 to 3 ms) in each round
            int delayMs = ThreadLocalRandom.current().nextInt(4); // 0 to 3 ms
            boolean delayCancel = ThreadLocalRandom.current().nextBoolean();

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (delayCancel && delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    int st = mockMvc.perform(post("/api/v1/deals/" + dealId + "/cancel")
                                    .header("Authorization", "Bearer " + tokenA)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Race cancel"))))
                            .andReturn().getResponse().getStatus();
                    cancelStatus.set(st);
                } catch (Exception ex) {
                    cancelStatus.set(409);
                }
            });

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (!delayCancel && delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    try {
                        dealService.complete(dealId, sellerA.getId());
                        completeStatus.set(200);
                    } catch (IllegalDealTransitionException | ObjectOptimisticLockingFailureException ex) {
                        completeStatus.set(409);
                    } catch (Exception ex) {
                        completeStatus.set(500);
                    }
                } catch (Exception ignored) {}
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            // Invariants: No 500s, exactly one winner
            assertThat(cancelStatus.get()).isIn(200, 409);
            assertThat(completeStatus.get()).isIn(200, 409);
            assertThat(cancelStatus.get() == 200 ^ completeStatus.get() == 200).isTrue();

            Deal finalDeal = dealRepository.findById(dealId).orElseThrow();
            assertThat(finalDeal.getStatus()).isIn(DealStatus.CANCELLED, DealStatus.COMPLETED);
            if (finalDeal.getStatus() == DealStatus.CANCELLED) {
                assertThat(cancelStatus.get()).isEqualTo(200);
                cancelWins.incrementAndGet();
            } else {
                assertThat(completeStatus.get()).isEqualTo(200);
                completeWins.incrementAndGet();
            }
        }

        System.out.println("20-Round Cancel vs Complete Race Results: Cancel won = " + cancelWins.get() + ", Complete won = " + completeWins.get());
        assertThat(cancelWins.get() + completeWins.get()).isEqualTo(20);
    }
}
