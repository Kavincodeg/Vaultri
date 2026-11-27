package com.vaultri.core.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.auth.service.AuthService;
import com.vaultri.core.auth.service.TokenService;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.service.DealService;
import com.vaultri.core.payment.gateway.FakeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DealActionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private DealService dealService;

    @Autowired
    private com.vaultri.core.audit.repository.AuditRepository auditRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private FakeGateway fakeGateway;

    private Seller sellerA;
    private String tokenA;

    private Seller sellerB;
    private String tokenB;

    @BeforeEach
    void setUp() {
        fakeGateway.reset();

        String emailA = "sellerA_" + UUID.randomUUID() + "@vaultri.com";
        authService.signup(new SignupRequest(emailA, "Password123!", "Seller A", null));
        sellerA = sellerRepository.findByEmailIgnoreCase(emailA).orElseThrow();
        tokenA = tokenService.generateAccessToken(sellerA);

        String emailB = "sellerB_" + UUID.randomUUID() + "@vaultri.com";
        authService.signup(new SignupRequest(emailB, "Password123!", "Seller B", null));
        sellerB = sellerRepository.findByEmailIgnoreCase(emailB).orElseThrow();
        tokenB = tokenService.generateAccessToken(sellerB);
    }

    private Deal createDealForSeller(String token, Long price, int depositPercent) throws Exception {
        String key = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Customer " + UUID.randomUUID(),
                "customer@example.com",
                "+919876543210",
                "Action Test Deal",
                price,
                depositPercent,
                null,
                "Terms"
        );
        String res = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID dealId = UUID.fromString(objectMapper.readTree(res).path("id").asText());
        return dealRepository.findById(dealId).orElseThrow();
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/start: transitions DEPOSIT_PAID to IN_PROGRESS, writes audit row, returns DealResponse")
    void testStartDealSuccess() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        // Advance to DEPOSIT_PAID
        dealService.markDepositPaid(deal.getId(), sellerA.getId());

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/start")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deal.getId().toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        Deal updated = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DealStatus.IN_PROGRESS);

        // Verify audit row written exactly once
        long inProgressLogs = auditRepository.findByDealId(deal.getId()).stream()
                .filter(a -> "IN_PROGRESS".equals(a.getAction()))
                .count();
        assertThat(inProgressLogs).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/start: repeated call returns 409 Conflict")
    void testStartDealRepeatedCallReturns409() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/start")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // Repeated call when already IN_PROGRESS
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/start")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal Deal Transition"));
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/start: 404 for unknown deal or another seller's deal (identical response)")
    void testStartDeal404Isolation() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());

        // Seller B trying to start Seller A's deal -> 404
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/start")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"));

        // Unknown deal ID -> 404
        UUID unknownId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/deals/" + unknownId + "/start")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"));
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/start: 401 without Bearer token")
    void testStartDeal401WithoutToken() throws Exception {
        UUID dealId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/start"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/complete: transitions IN_PROGRESS to COMPLETED, writes audit row, returns DealResponse")
    void testCompleteDealSuccess() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());
        dealService.startProgress(deal.getId(), sellerA.getId());

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deal.getId().toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Deal updated = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DealStatus.COMPLETED);

        long completedLogs = auditRepository.findByDealId(deal.getId()).stream()
                .filter(a -> "COMPLETED".equals(a.getAction()))
                .count();
        assertThat(completedLogs).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/complete: repeated call returns 409 Conflict")
    void testCompleteDealRepeatedCallReturns409() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());
        dealService.startProgress(deal.getId(), sellerA.getId());

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // Repeated complete on terminal COMPLETED status
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal Deal Transition"));
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/complete: call from DRAFT or DEPOSIT_PAID returns 409 Conflict")
    void testCompleteDealFromInvalidStatusReturns409() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        // Deal is currently in DEPOSIT_PENDING
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal Deal Transition"));

        // Advance to DEPOSIT_PAID (still cannot complete directly without IN_PROGRESS)
        dealService.markDepositPaid(deal.getId(), sellerA.getId());
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Illegal Deal Transition"));
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/complete: 404 for unknown deal or another seller's deal")
    void testCompleteDeal404Isolation() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());
        dealService.startProgress(deal.getId(), sellerA.getId());

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"));
    }

    @Test
    @DisplayName("POST /api/v1/deals/{id}/complete: 401 without Bearer token")
    void testCompleteDeal401WithoutToken() throws Exception {
        UUID dealId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/complete"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Complete vs Cancel deterministic ordering: (a) complete first -> cancel returns 409, deal stays COMPLETED")
    void testCompleteThenCancelDeterministic() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());
        dealService.startProgress(deal.getId(), sellerA.getId());

        // Complete first
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // Cancel attempt must fail with 409
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancel after complete"))))
                .andExpect(status().isConflict());

        Deal finalDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(finalDeal.getStatus()).isEqualTo(DealStatus.COMPLETED);
    }

    @Test
    @DisplayName("Complete vs Cancel deterministic ordering: (b) cancel first -> complete returns 409, deal stays CANCELLED")
    void testCancelThenCompleteDeterministic() throws Exception {
        Deal deal = createDealForSeller(tokenA, 100000L, 20);
        dealService.markDepositPaid(deal.getId(), sellerA.getId());
        dealService.startProgress(deal.getId(), sellerA.getId());

        // Cancel first
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancel before complete"))))
                .andExpect(status().isOk());

        // Complete attempt must fail with 409
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict());

        Deal finalDeal = dealRepository.findById(deal.getId()).orElseThrow();
        assertThat(finalDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);
    }
}
