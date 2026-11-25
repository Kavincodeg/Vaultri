package com.vaultri.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.dto.LoginRequest;
import com.vaultri.core.auth.dto.SignupRequest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderKind;
import com.vaultri.core.reminder.entity.ReminderStatus;
import com.vaultri.core.reminder.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReportIntegrationTest extends AbstractIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private String tokenA;
    private String tokenB;
    private UUID sellerIdA;
    private UUID sellerIdB;

    @BeforeEach
    void setUp() throws Exception {
        // Seller A
        String emailA = "seller_rep_a_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailA, "Password123!", "Seller A", null))))
                .andExpect(status().isCreated());
        MvcResult loginA = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(emailA, "Password123!"))))
                .andExpect(status().isOk())
                .andReturn();
        tokenA = objectMapper.readTree(loginA.getResponse().getContentAsString()).path("accessToken").asText();
        sellerIdA = sellerRepository.findByEmailIgnoreCase(emailA).orElseThrow().getId();

        // Seller B
        String emailB = "seller_rep_b_" + UUID.randomUUID() + "@vaultri.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupRequest(emailB, "Password123!", "Seller B", null))))
                .andExpect(status().isCreated());
        MvcResult loginB = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(emailB, "Password123!"))))
                .andExpect(status().isOk())
                .andReturn();
        tokenB = objectMapper.readTree(loginB.getResponse().getContentAsString()).path("accessToken").asText();
        sellerIdB = sellerRepository.findByEmailIgnoreCase(emailB).orElseThrow().getId();
    }

    private Deal seedDeal(Seller seller, DealStatus status, long price, long depositAmount, Instant createdAt) {
        Deal deal = Deal.builder()
                .seller(seller)
                .customerName("Secret Customer " + UUID.randomUUID())
                .customerEmail("customer_" + UUID.randomUUID() + "@domain.com")
                .customerPhone("+919876543210")
                .title("Report Test Deal")
                .price(price)
                .depositPercent((int) ((depositAmount * 100) / price))
                .depositAmount(depositAmount)
                .dueDate(createdAt.plusSeconds(86400 * 7))
                .status(status)
                .contractText("Standard Terms")
                .version(0L)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
        Deal saved = dealRepository.save(deal);
        // Force exact createdAt in database because @CreationTimestamp might override
        jdbcTemplate.update("UPDATE deals SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private Payment seedPayment(Deal deal, PaymentType type, long amount, PaymentStatus status) {
        Payment payment = Payment.builder()
                .deal(deal)
                .type(type)
                .amount(amount)
                .status(status)
                .razorpayLinkId("plink_" + UUID.randomUUID())
                .razorpayPaymentId(status == PaymentStatus.PAID ? "pay_" + UUID.randomUUID() : null)
                .createdAt(deal.getCreatedAt())
                .updatedAt(deal.getCreatedAt())
                .build();
        return paymentRepository.save(payment);
    }

    private void seedLedgerEntry(UUID dealId, UUID sellerId, UUID paymentId, String type, long amount, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, deal_id, seller_id, payment_id, type, amount, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, sellerId, paymentId, type, amount, java.sql.Timestamp.from(createdAt)
        );
    }

    private Reminder seedReminder(Deal deal, ReminderKind kind, ReminderStatus status) {
        Reminder reminder = Reminder.builder()
                .deal(deal)
                .channel("EMAIL")
                .message("Reminder message")
                .kind(kind)
                .status(status)
                .attempts(status == ReminderStatus.SENT ? 1 : 0)
                .dueAt(Instant.now().minusSeconds(3600))
                .sentAt(status == ReminderStatus.SENT ? Instant.now() : null)
                .createdAt(deal.getCreatedAt())
                .build();
        return reminderRepository.save(reminder);
    }

    @Test
    @DisplayName("Summary Report: isolated metrics for Seller A unaffected by Seller B")
    void testSummaryReportSellerIsolationAndMetrics() throws Exception {
        Seller sellerA = sellerRepository.findById(sellerIdA).orElseThrow();
        Seller sellerB = sellerRepository.findById(sellerIdB).orElseThrow();

        // Seller A:
        // Deal A1: COMPLETED, price 100000, deposit 20000, paid + ledger entry
        Instant t1 = Instant.parse("2026-01-10T10:00:00Z");
        Deal dA1 = seedDeal(sellerA, DealStatus.COMPLETED, 100000L, 20000L, t1);
        Payment pA1 = seedPayment(dA1, PaymentType.DEPOSIT, 20000L, PaymentStatus.PAID);
        seedLedgerEntry(dA1.getId(), sellerA.getId(), pA1.getId(), "DEPOSIT", 20000L, t1);

        // Deal A2: CANCELLED, price 200000, deposit 40000, cancellation fee 10000, both ledger entries
        Instant t2 = Instant.parse("2026-01-20T10:00:00Z");
        Deal dA2 = seedDeal(sellerA, DealStatus.CANCELLED, 200000L, 40000L, t2);
        Payment pA2Dep = seedPayment(dA2, PaymentType.DEPOSIT, 40000L, PaymentStatus.PAID);
        seedLedgerEntry(dA2.getId(), sellerA.getId(), pA2Dep.getId(), "DEPOSIT", 40000L, t2);
        Payment pA2Fee = seedPayment(dA2, PaymentType.CANCELLATION_FEE, 10000L, PaymentStatus.PAID);
        seedLedgerEntry(dA2.getId(), sellerA.getId(), pA2Fee.getId(), "CANCELLATION_FEE", 10000L, t2);

        // Deal A3: DEPOSIT_PENDING, price 300000, deposit 60000, unpaid (no ledger entry)
        Instant t3 = Instant.parse("2026-02-05T10:00:00Z");
        Deal dA3 = seedDeal(sellerA, DealStatus.DEPOSIT_PENDING, 300000L, 60000L, t3);
        seedPayment(dA3, PaymentType.DEPOSIT, 60000L, PaymentStatus.CREATED);

        // Seller B (should NOT leak into Seller A's report):
        Instant tb = Instant.parse("2026-01-15T10:00:00Z");
        Deal dB1 = seedDeal(sellerB, DealStatus.COMPLETED, 500000L, 100000L, tb);
        Payment pB1 = seedPayment(dB1, PaymentType.DEPOSIT, 100000L, PaymentStatus.PAID);
        seedLedgerEntry(dB1.getId(), sellerB.getId(), pB1.getId(), "DEPOSIT", 100000L, tb);

        // Request Seller A report for 2026-01-01 to 2026-02-28
        MvcResult resA = mockMvc.perform(get("/api/v1/reports/summary")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2026-01-01")
                .param("to", "2026-02-28"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonA = objectMapper.readTree(resA.getResponse().getContentAsString());

        // Assert exact numbers for Seller A
        assertThat(jsonA.path("totalDeals").asLong()).isEqualTo(3L);
        assertThat(jsonA.path("totalDealValue").asLong()).isEqualTo(600000L);
        assertThat(jsonA.path("totalDepositsPaid").asLong()).isEqualTo(60000L); // 20000 + 40000
        assertThat(jsonA.path("totalCancellationFeesCollected").asLong()).isEqualTo(10000L);
        assertThat(jsonA.path("cancelledDealsCount").asLong()).isEqualTo(1L);

        JsonNode statusCountsA = jsonA.path("dealsByStatus");
        assertThat(statusCountsA.path("COMPLETED").asLong()).isEqualTo(1L);
        assertThat(statusCountsA.path("CANCELLED").asLong()).isEqualTo(1L);
        assertThat(statusCountsA.path("DEPOSIT_PENDING").asLong()).isEqualTo(1L);
        assertThat(statusCountsA.path("DRAFT").asLong()).isEqualTo(0L);

        // Safety check: Response contains NO customer PII
        String bodyString = resA.getResponse().getContentAsString();
        assertThat(bodyString).doesNotContain("Secret Customer");
        assertThat(bodyString).doesNotContain("@domain.com");
        assertThat(bodyString).doesNotContain("+919876543210");

        // Request Seller B report and verify isolation
        MvcResult resB = mockMvc.perform(get("/api/v1/reports/summary")
                .header("Authorization", "Bearer " + tokenB)
                .param("from", "2026-01-01")
                .param("to", "2026-02-28"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonB = objectMapper.readTree(resB.getResponse().getContentAsString());
        assertThat(jsonB.path("totalDeals").asLong()).isEqualTo(1L);
        assertThat(jsonB.path("totalDealValue").asLong()).isEqualTo(500000L);
        assertThat(jsonB.path("totalDepositsPaid").asLong()).isEqualTo(100000L);
        assertThat(jsonB.path("totalCancellationFeesCollected").asLong()).isEqualTo(0L);
        assertThat(jsonB.path("cancelledDealsCount").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Deals By Month Report: monthly breakdown in UTC with deposits collected")
    void testDealsByMonthReport() throws Exception {
        Seller sellerA = sellerRepository.findById(sellerIdA).orElseThrow();

        // Month 1: 2026-01 (2 deals: 1 COMPLETED with 20k deposit, 1 CANCELLED with 40k deposit)
        Instant t1 = Instant.parse("2026-01-10T12:00:00Z");
        Deal dA1 = seedDeal(sellerA, DealStatus.COMPLETED, 100000L, 20000L, t1);
        Payment pA1 = seedPayment(dA1, PaymentType.DEPOSIT, 20000L, PaymentStatus.PAID);
        seedLedgerEntry(dA1.getId(), sellerA.getId(), pA1.getId(), "DEPOSIT", 20000L, t1);

        Instant t2 = Instant.parse("2026-01-25T12:00:00Z");
        Deal dA2 = seedDeal(sellerA, DealStatus.CANCELLED, 200000L, 40000L, t2);
        Payment pA2 = seedPayment(dA2, PaymentType.DEPOSIT, 40000L, PaymentStatus.PAID);
        seedLedgerEntry(dA2.getId(), sellerA.getId(), pA2.getId(), "DEPOSIT", 40000L, t2);

        // Month 2: 2026-02 (1 deal: DEPOSIT_PENDING, 0 deposit collected)
        Instant t3 = Instant.parse("2026-02-15T12:00:00Z");
        seedDeal(sellerA, DealStatus.DEPOSIT_PENDING, 300000L, 60000L, t3);

        MvcResult res = mockMvc.perform(get("/api/v1/reports/deals-by-month")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2026-01-01")
                .param("to", "2026-02-28"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode array = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);

        // January
        JsonNode jan = array.get(0);
        assertThat(jan.path("month").asText()).isEqualTo("2026-01");
        assertThat(jan.path("dealsCreated").asLong()).isEqualTo(2L);
        assertThat(jan.path("dealsCompleted").asLong()).isEqualTo(1L);
        assertThat(jan.path("dealsCancelled").asLong()).isEqualTo(1L);
        assertThat(jan.path("depositsCollected").asLong()).isEqualTo(60000L);

        // February
        JsonNode feb = array.get(1);
        assertThat(feb.path("month").asText()).isEqualTo("2026-02");
        assertThat(feb.path("dealsCreated").asLong()).isEqualTo(1L);
        assertThat(feb.path("dealsCompleted").asLong()).isEqualTo(0L);
        assertThat(feb.path("dealsCancelled").asLong()).isEqualTo(0L);
        assertThat(feb.path("depositsCollected").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Reminders Report: counts by status and kind for seller's deals")
    void testRemindersReport() throws Exception {
        Seller sellerA = sellerRepository.findById(sellerIdA).orElseThrow();
        Seller sellerB = sellerRepository.findById(sellerIdB).orElseThrow();

        // Seller A: 2 deals
        Instant now = Instant.now();
        Deal dA1 = seedDeal(sellerA, DealStatus.DEPOSIT_PENDING, 100000L, 20000L, now);
        Deal dA2 = seedDeal(sellerA, DealStatus.DEPOSIT_PENDING, 200000L, 40000L, now);

        // Reminders for Seller A:
        seedReminder(dA1, ReminderKind.SCHEDULED, ReminderStatus.PENDING);
        seedReminder(dA1, ReminderKind.SCHEDULED, ReminderStatus.SENT);
        seedReminder(dA2, ReminderKind.MANUAL, ReminderStatus.PENDING);

        // Seller B: reminder for Seller B (must not affect Seller A)
        Deal dB = seedDeal(sellerB, DealStatus.DEPOSIT_PENDING, 500000L, 100000L, now);
        seedReminder(dB, ReminderKind.SCHEDULED, ReminderStatus.PENDING);
        seedReminder(dB, ReminderKind.MANUAL, ReminderStatus.FAILED);

        MvcResult resA = mockMvc.perform(get("/api/v1/reports/reminders")
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonA = objectMapper.readTree(resA.getResponse().getContentAsString());
        assertThat(jsonA.path("total").asLong()).isEqualTo(3L);

        JsonNode byStatusA = jsonA.path("byStatus");
        assertThat(byStatusA.path("PENDING").asLong()).isEqualTo(2L);
        assertThat(byStatusA.path("SENT").asLong()).isEqualTo(1L);
        assertThat(byStatusA.path("FAILED").asLong()).isEqualTo(0L);
        assertThat(byStatusA.path("CANCELLED").asLong()).isEqualTo(0L);

        JsonNode byKindA = jsonA.path("byKind");
        assertThat(byKindA.path("SCHEDULED").asLong()).isEqualTo(2L);
        assertThat(byKindA.path("MANUAL").asLong()).isEqualTo(1L);

        JsonNode byStatusAndKindA = jsonA.path("byStatusAndKind");
        assertThat(byStatusAndKindA.path("PENDING").path("SCHEDULED").asLong()).isEqualTo(1L);
        assertThat(byStatusAndKindA.path("PENDING").path("MANUAL").asLong()).isEqualTo(1L);
        assertThat(byStatusAndKindA.path("SENT").path("SCHEDULED").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Empty Range: returns 200 with zeroes and empty lists")
    void testEmptyRangeReturnsZeroes() throws Exception {
        // Range with no deals
        MvcResult resSummary = mockMvc.perform(get("/api/v1/reports/summary")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2024-01-01")
                .param("to", "2024-01-31"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode summary = objectMapper.readTree(resSummary.getResponse().getContentAsString());
        assertThat(summary.path("totalDeals").asLong()).isEqualTo(0L);
        assertThat(summary.path("totalDealValue").asLong()).isEqualTo(0L);
        assertThat(summary.path("totalDepositsPaid").asLong()).isEqualTo(0L);
        assertThat(summary.path("totalCancellationFeesCollected").asLong()).isEqualTo(0L);
        assertThat(summary.path("cancelledDealsCount").asLong()).isEqualTo(0L);

        MvcResult resMonthly = mockMvc.perform(get("/api/v1/reports/deals-by-month")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2024-01-01")
                .param("to", "2024-01-31"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode monthly = objectMapper.readTree(resMonthly.getResponse().getContentAsString());
        assertThat(monthly.isArray()).isTrue();
        assertThat(monthly.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Date Validation: invalid dates, from > to, and range > 366 days return 400")
    void testDateRangeValidation() throws Exception {
        // Invalid date format
        mockMvc.perform(get("/api/v1/reports/summary")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "invalid-date")
                .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());

        // from > to
        mockMvc.perform(get("/api/v1/reports/summary")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2026-03-01")
                .param("to", "2026-02-01"))
                .andExpect(status().isBadRequest());

        // Range > 366 days (e.g. 370 days)
        mockMvc.perform(get("/api/v1/reports/summary")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2024-01-01")
                .param("to", "2025-01-10"))
                .andExpect(status().isBadRequest());

        // Same validation applies to deals-by-month
        mockMvc.perform(get("/api/v1/reports/deals-by-month")
                .header("Authorization", "Bearer " + tokenA)
                .param("from", "2026-03-01")
                .param("to", "2026-02-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Authentication: unauthenticated requests return 401")
    void testUnauthenticatedAccessReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/reports/deals-by-month"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/reports/reminders"))
                .andExpect(status().isUnauthorized());
    }
}
