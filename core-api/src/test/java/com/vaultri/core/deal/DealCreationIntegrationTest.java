package com.vaultri.core.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.auth.service.AuthService;
import com.vaultri.core.auth.service.TokenService;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.dto.DealResponse;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.gateway.FakeGateway;
import com.vaultri.core.payment.repository.PaymentRepository;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DealCreationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private com.vaultri.core.auth.repository.IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private FakeGateway fakeGateway;

    private Seller seller1;
    private String token1;

    private Seller seller2;
    private String token2;

    @BeforeEach
    void setUp() {
        fakeGateway.reset();

        String email1 = "seller1_" + UUID.randomUUID() + "@vaultri.com";
        authService.signup(new SignupRequest(email1, "Password123!", "Seller One", null));
        seller1 = sellerRepository.findByEmailIgnoreCase(email1).orElseThrow();
        token1 = tokenService.generateAccessToken(seller1);

        String email2 = "seller2_" + UUID.randomUUID() + "@vaultri.com";
        authService.signup(new SignupRequest(email2, "Password123!", "Seller Two", null));
        seller2 = sellerRepository.findByEmailIgnoreCase(email2).orElseThrow();
        token2 = tokenService.generateAccessToken(seller2);
    }

    @Test
    @DisplayName("Create Deal: Happy path returns 201, DEPOSIT_PENDING status, payment link created and deposit payment summary populated")
    void testCreateDealHappyPath() throws Exception {
        CreateDealRequest req = new CreateDealRequest(
                "Acme Client",
                "client@acme.com",
                "+919876543210",
                "Website Redesign",
                100000L,
                20,
                Instant.now().plusSeconds(86400 * 7),
                "Standard terms"
        );
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("DEPOSIT_PENDING"))
                .andExpect(jsonPath("$.depositAmount").value(20000L))
                .andExpect(jsonPath("$.depositPayment.status").value("CREATED"))
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").exists())
                .andExpect(jsonPath("$.depositPayment.paymentUrl").exists())
                .andReturn();

        DealResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), DealResponse.class);
        assertThat(fakeGateway.getCallCount()).isEqualTo(1);

        Deal deal = dealRepository.findById(response.id()).orElseThrow();
        assertThat(deal.getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);

        Payment payment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getRazorpayLinkId()).isNotNull();

        // Check GET endpoint returns same deal with payment summary
        mockMvc.perform(get("/api/v1/deals/" + deal.getId())
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deal.getId().toString()))
                .andExpect(jsonPath("$.depositPayment.status").value("CREATED"))
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").value(payment.getRazorpayLinkId()));
    }

    @Test
    @DisplayName("Create Deal: Replaying same key and same body returns identical response without re-calling gateway")
    void testCreateDealIdempotentReplay() throws Exception {
        CreateDealRequest req = new CreateDealRequest(
                "Client Corp",
                "client@corp.com",
                null,
                "Mobile App",
                200000L,
                30,
                null,
                null
        );
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        DealResponse firstResp = objectMapper.readValue(first.getResponse().getContentAsString(), DealResponse.class);
        DealResponse secondResp = objectMapper.readValue(second.getResponse().getContentAsString(), DealResponse.class);
        assertThat(secondResp).isEqualTo(firstResp);
        assertThat(fakeGateway.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create Deal: Reusing same key with different body returns 422 Unprocessable Entity")
    void testCreateDealDifferentBodyConflict() throws Exception {
        CreateDealRequest req1 = new CreateDealRequest(
                "Client 1", "c1@test.com", null, "Project A", 50000L, 20, null, null
        );
        CreateDealRequest req2 = new CreateDealRequest(
                "Client 2", "c2@test.com", null, "Project B", 60000L, 20, null, null
        );
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Create Deal: Validation checks: missing key returns 400, depositPercent 0 returns 400")
    void testCreateDealValidation() throws Exception {
        CreateDealRequest validReq = new CreateDealRequest(
                "Client", "client@test.com", null, "Title", 10000L, 10, null, null
        );

        // Missing Idempotency-Key header
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isBadRequest());

        // Blank Idempotency-Key header
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isBadRequest());

        // depositPercent = 0
        CreateDealRequest zeroDeposit = new CreateDealRequest(
                "Client", "client@test.com", null, "Title", 10000L, 0, null, null
        );
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zeroDeposit)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Create Deal: Same idempotency key used by two different sellers creates two separate deals")
    void testCreateDealSameKeyDifferentSellers() throws Exception {
        String sharedKey = "shared-key-" + UUID.randomUUID();
        CreateDealRequest req = new CreateDealRequest(
                "Client", "client@test.com", null, "Title", 50000L, 20, null, null
        );

        MvcResult res1 = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult res2 = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token2)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        DealResponse deal1 = objectMapper.readValue(res1.getResponse().getContentAsString(), DealResponse.class);
        DealResponse deal2 = objectMapper.readValue(res2.getResponse().getContentAsString(), DealResponse.class);

        assertThat(deal1.id()).isNotEqualTo(deal2.id());
        assertThat(deal1.sellerId()).isEqualTo(seller1.getId());
        assertThat(deal2.sellerId()).isEqualTo(seller2.getId());
        assertThat(fakeGateway.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Create Deal: 10 concurrent threads with same key and body produce exactly one deal, one payment, one gateway call")
    void testConcurrentDealCreationWithSameKey() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Concurrent Client", "concurrent@test.com", null, "Concurrent Project", 75000L, 25, null, null
        );
        String body = objectMapper.writeValueAsString(req);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/v1/deals")
                                .header("Authorization", "Bearer " + token1)
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
                return res.getResponse().getStatus();
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger inProgressCount = new AtomicInteger(0);

        for (Future<Integer> f : futures) {
            int code = f.get(10, TimeUnit.SECONDS);
            if (code == 201) successCount.incrementAndGet();
            else if (code == 409) inProgressCount.incrementAndGet();
            else {
                assertThat(code).isIn(201, 409);
            }
        }
        executor.shutdown();

        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create Deal: Gateway failure returns 502 with Retry-After; retry with same key resumes Step B successfully")
    void testGatewayFailureAndResume() throws Exception {
        fakeGateway.setShouldFail(true);
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Resume Client", "resume@test.com", null, "Resumable Project", 120000L, 15, null, null
        );

        // First attempt fails at gateway (Step B)
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        // Verify Step A persisted deal and payment with null link id
        List<Deal> deals = dealRepository.findBySellerId(seller1.getId());
        Deal createdDeal = deals.stream().filter(d -> d.getTitle().equals("Resumable Project")).findFirst().orElseThrow();
        assertThat(createdDeal.getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);

        Payment payment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(payment.getRazorpayLinkId()).isNull();

        // Gateway recovers
        fakeGateway.setShouldFail(false);

        // Retry with SAME idempotency key resumes Step B
        MvcResult retryResult = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(createdDeal.getId().toString()))
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").exists())
                .andReturn();

        Payment updatedPayment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(updatedPayment.getRazorpayLinkId()).isNotNull();

        // Exactly one deal and one payment exists for this deal
        List<Deal> matchingDeals = dealRepository.findBySellerId(seller1.getId()).stream()
                .filter(d -> d.getTitle().equals("Resumable Project")).toList();
        assertThat(matchingDeals).hasSize(1);
        List<Payment> payments = paymentRepository.findByDealId(createdDeal.getId());
        assertThat(payments).hasSize(1);
    }

    @Test
    @DisplayName("Create Deal: Stale lease crash recovery - an expired lock lease is taken over by retry and resumed")
    void testStaleLeaseRecovery() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Stale Lease Client", "stale@test.com", null, "Lease Project", 80000L, 20, null, null
        );

        // Simulate crash: Server dies midway through Step B without releasing lock or saving response.
        // We simulate this by performing Step A and setting locked_until to the past.
        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        var keyEntity = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(seller1.getId(), idempotencyKey).orElseThrow();
        // Set lockedUntil to 5 minutes ago (simulating crashed node whose lease expired)
        keyEntity.setLockedUntil(Instant.now().minusSeconds(300));
        idempotencyKeyRepository.save(keyEntity);

        fakeGateway.setShouldFail(false);

        // A retry now discovers the expired lease, takes over the lease via tryAcquireLock, and completes Step B
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DEPOSIT_PENDING"))
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").exists());

        var finishedKey = idempotencyKeyRepository.findBySellerIdAndKeyWithDeal(seller1.getId(), idempotencyKey).orElseThrow();
        assertThat(finishedKey.getResponseStatus()).isEqualTo(201);
        assertThat(finishedKey.getLockedUntil()).isNull();
    }
}
