package com.vaultri.core.deal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.auth.service.AuthService;
import com.vaultri.core.auth.service.TokenService;
import com.vaultri.core.deal.dto.CancelDealRequest;
import com.vaultri.core.deal.dto.CreateDealRequest;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.deal.service.DealService;
import com.vaultri.core.payment.gateway.FakeGateway;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.reminder.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReadEndpointsIntegrationTest extends AbstractIntegrationTest {

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
    private ReminderRepository reminderRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private DealService dealService;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private FakeGateway fakeGateway;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

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

    private Deal createDeal(String token, String customerName, String customerEmail, String customerPhone, String title, Long price) throws Exception {
        String key = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                customerName,
                customerEmail,
                customerPhone,
                title,
                price,
                20,
                null,
                "Contract text"
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

    // ─────────────────────────────────────────────────────────────
    // Exact Top-Level Fields Verification
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Stable pagination shape: asserts exact top-level field names {items, page, size, totalElements, totalPages}")
    void testExactTopLevelPaginationFieldNames() throws Exception {
        createDeal(tokenA, "Client FieldTest", "field@example.com", null, "Field Deal", 100000L);

        MvcResult res = mockMvc.perform(get("/api/v1/deals?page=0&size=10")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(root.isObject()).isTrue();

        List<String> fieldNames = new ArrayList<>();
        root.fieldNames().forEachRemaining(fieldNames::add);

        assertThat(fieldNames).containsExactlyInAnyOrder("items", "page", "size", "totalElements", "totalPages");
        assertThat(root.path("items").isArray()).isTrue();
        assertThat(root.path("page").isInt()).isTrue();
        assertThat(root.path("size").isInt()).isTrue();
        assertThat(root.path("totalElements").isIntegralNumber()).isTrue();
        assertThat(root.path("totalPages").isInt()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────
    // 1. GET /api/v1/deals
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/deals: scoped to calling seller, Seller A never sees Seller B's deals")
    void testDealsSellerIsolation() throws Exception {
        Deal dealA = createDeal(tokenA, "Client A", "clienta@example.com", "+919000000001", "Deal A", 100000L);
        Deal dealB = createDeal(tokenB, "Client B", "clientb@example.com", "+919000000002", "Deal B", 200000L);

        // Seller A sees only dealA
        MvcResult resA = mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonA = objectMapper.readTree(resA.getResponse().getContentAsString());
        assertThat(jsonA.path("items")).hasSize(1);
        assertThat(jsonA.path("items").get(0).path("id").asText()).isEqualTo(dealA.getId().toString());

        // Seller B sees only dealB
        MvcResult resB = mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonB = objectMapper.readTree(resB.getResponse().getContentAsString());
        assertThat(jsonB.path("items")).hasSize(1);
        assertThat(jsonB.path("items").get(0).path("id").asText()).isEqualTo(dealB.getId().toString());
    }

    @Test
    @DisplayName("GET /api/v1/deals: fixed ordering created_at DESC, id DESC and pagination boundaries")
    void testDealsOrderingAndPagination() throws Exception {
        Deal deal1 = createDeal(tokenA, "Client 1", "c1@test.com", null, "Deal 1", 100000L);
        Deal deal2 = createDeal(tokenA, "Client 2", "c2@test.com", null, "Deal 2", 200000L);
        Deal deal3 = createDeal(tokenA, "Client 3", "c3@test.com", null, "Deal 3", 300000L);

        // Page 0, size 2
        MvcResult res0 = mockMvc.perform(get("/api/v1/deals?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json0 = objectMapper.readTree(res0.getResponse().getContentAsString());
        assertThat(json0.path("items")).hasSize(2);
        assertThat(json0.path("totalElements").asInt()).isEqualTo(3);
        assertThat(json0.path("page").asInt()).isEqualTo(0);
        assertThat(json0.path("size").asInt()).isEqualTo(2);
        assertThat(json0.path("totalPages").asInt()).isEqualTo(2);
        // Most recent first: deal3 then deal2
        assertThat(json0.path("items").get(0).path("id").asText()).isEqualTo(deal3.getId().toString());
        assertThat(json0.path("items").get(1).path("id").asText()).isEqualTo(deal2.getId().toString());

        // Page 1, size 2 -> should return deal1
        MvcResult res1 = mockMvc.perform(get("/api/v1/deals?page=1&size=2")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json1 = objectMapper.readTree(res1.getResponse().getContentAsString());
        assertThat(json1.path("items")).hasSize(1);
        assertThat(json1.path("items").get(0).path("id").asText()).isEqualTo(deal1.getId().toString());
    }

    @Test
    @DisplayName("GET /api/v1/deals: status filtering and invalid status validation (400)")
    void testDealsStatusFiltering() throws Exception {
        Deal deal1 = createDeal(tokenA, "Client 1", "c1@test.com", null, "Deal 1", 100000L);
        dealService.markDepositPaid(deal1.getId(), sellerA.getId());
        dealService.startProgress(deal1.getId(), sellerA.getId());
        dealService.complete(deal1.getId(), sellerA.getId()); // COMPLETED

        Deal deal2 = createDeal(tokenA, "Client 2", "c2@test.com", null, "Deal 2", 200000L); // DEPOSIT_PENDING

        // Filter by COMPLETED
        MvcResult res = mockMvc.perform(get("/api/v1/deals?status=COMPLETED")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.path("items")).hasSize(1);
        assertThat(json.path("items").get(0).path("status").asText()).isEqualTo("COMPLETED");

        // Invalid status returns 400
        mockMvc.perform(get("/api/v1/deals?status=INVALID_STATUS")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/deals: size 101 -> 400, page < 0 -> 400, empty list -> 200 with empty page, 401 without token")
    void testDealsValidation() throws Exception {
        // Size 101 -> 400
        mockMvc.perform(get("/api/v1/deals?size=101")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());

        // Negative page -> 400
        mockMvc.perform(get("/api/v1/deals?page=-1")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());

        // Empty list -> 200 with empty page
        MvcResult res = mockMvc.perform(get("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.path("items")).isEmpty();
        assertThat(json.path("totalElements").asInt()).isEqualTo(0);

        // 401 without token
        mockMvc.perform(get("/api/v1/deals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/deals: fetches deposit and fee payments in ONE extra query (not 1 per deal)")
    void testDealsPaymentQueryCount() throws Exception {
        createDeal(tokenA, "Client 1", "c1@test.com", null, "Deal 1", 100000L);
        createDeal(tokenA, "Client 2", "c2@test.com", null, "Deal 2", 200000L);
        createDeal(tokenA, "Client 3", "c3@test.com", null, "Deal 3", 300000L);

        org.hibernate.SessionFactory sessionFactory = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class);
        org.hibernate.stat.Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mockMvc.perform(get("/api/v1/deals?page=0&size=10")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        // 1 count query + 1 deal query + 1 payment query = 3 queries total (never 1 + 2*N queries)
        long totalQueries = stats.getPrepareStatementCount();
        assertThat(totalQueries).isLessThanOrEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. GET /api/v1/payments
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/payments: returns id, dealId, dealTitle, type, status, amount, paymentUrl, createdAt; isolated by seller")
    void testPaymentsList() throws Exception {
        Deal dealA = createDeal(tokenA, "Client A", "clienta@example.com", "+919000000001", "Deal A Title", 100000L);
        Deal dealB = createDeal(tokenB, "Client B", "clientb@example.com", "+919000000002", "Deal B Title", 200000L);

        MvcResult res = mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.path("items")).hasSize(1);

        JsonNode p = json.path("items").get(0);
        assertThat(p.path("id").asText()).isNotBlank();
        assertThat(p.path("dealId").asText()).isEqualTo(dealA.getId().toString());
        assertThat(p.path("dealTitle").asText()).isEqualTo("Deal A Title");
        assertThat(p.path("type").asText()).isEqualTo("DEPOSIT");
        assertThat(p.path("status").asText()).isEqualTo("CREATED");
        assertThat(p.path("amount").asLong()).isEqualTo(dealA.getDepositAmount());
        assertThat(p.has("paymentUrl")).isTrue();
        assertThat(p.path("createdAt").asText()).isNotBlank();

        // Must not contain customer email, phone, or seller B payments
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("clienta@example.com");
        assertThat(body).doesNotContain("+919000000001");
        assertThat(body).doesNotContain(dealB.getId().toString());

        // 401 without token
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────
    // 3. GET /api/v1/reminders
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/reminders: returns id, dealId, dealTitle, kind, status, dueAt, sentAt, attempts; never returns recipient or body")
    void testRemindersList() throws Exception {
        // Due date in future so scheduled reminder is created
        String key = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Client Rem",
                "secret_recipient@example.com",
                "+919876543210",
                "Reminder Deal Title",
                100000L,
                20,
                java.time.Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS),
                "Terms"
        );
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        MvcResult res = mockMvc.perform(get("/api/v1/reminders")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.path("items")).isNotEmpty();

        JsonNode r = json.path("items").get(0);
        assertThat(r.path("id").asText()).isNotBlank();
        assertThat(r.path("dealId").asText()).isNotBlank();
        assertThat(r.path("dealTitle").asText()).isEqualTo("Reminder Deal Title");
        assertThat(r.path("kind").asText()).isNotBlank();
        assertThat(r.path("status").asText()).isNotBlank();
        assertThat(r.path("dueAt").asText()).isNotBlank();
        assertThat(r.has("sentAt")).isTrue();
        assertThat(r.has("attempts")).isTrue();

        // Privacy assertion: never return recipient addresses or message bodies
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret_recipient@example.com");
        assertThat(body).doesNotContain("recipient");
        assertThat(body).doesNotContain("message");

        // 401 without token
        mockMvc.perform(get("/api/v1/reminders"))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────
    // 4. GET /api/v1/audit-logs and GET /api/v1/deals/{id}/audit-logs
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/audit-logs: returns id, dealId, action, createdAt, detail (sanitized fixed description)")
    void testAuditLogsList() throws Exception {
        Deal deal = createDeal(tokenA, "Client Secret", "client_secret@example.com", "+919999999999", "Audit Deal", 100000L);

        // Cancel deal with free-text reason containing sensitive info
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelDealRequest(0L, "Cancelled due to customer client_secret@example.com phone +919999999999"))))
                .andExpect(status().isOk());

        // Global audit logs for seller
        MvcResult resAll = mockMvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        String bodyAll = resAll.getResponse().getContentAsString();
        // Raw customer email and phone must NOT appear in audit logs response
        assertThat(bodyAll).doesNotContain("client_secret@example.com");
        assertThat(bodyAll).doesNotContain("+919999999999");

        // Specific deal audit logs
        MvcResult resDeal = mockMvc.perform(get("/api/v1/deals/" + deal.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jsonDeal = objectMapper.readTree(resDeal.getResponse().getContentAsString());
        assertThat(jsonDeal.path("items")).isNotEmpty();
        for (JsonNode item : jsonDeal.path("items")) {
            assertThat(item.path("id").asText()).isNotBlank();
            assertThat(item.path("dealId").asText()).isEqualTo(deal.getId().toString());
            assertThat(item.path("action").asText()).isNotBlank();
            assertThat(item.path("createdAt").asText()).isNotBlank();
            assertThat(item.path("detail").asText()).isNotBlank();
        }

        // Deal audit logs 404 isolation: Seller B requesting Seller A's deal audit logs -> 404
        mockMvc.perform(get("/api/v1/deals/" + deal.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Deal Not Found"));

        // 401 without token
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/deals/" + deal.getId() + "/audit-logs"))
                .andExpect(status().isUnauthorized());
    }
}
