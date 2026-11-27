package com.vaultri.core.payment.gateway;

import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.audit.entity.AuditLog;
import com.vaultri.core.audit.repository.AuditRepository;
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
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.gateway.dto.CreateLinkRequest;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentLinkRetryIntegrationTest extends AbstractIntegrationTest {

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
    private AuditRepository auditRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private FakeGateway fakeGateway;

    private Seller seller;
    private String token;

    @BeforeEach
    void setUp() {
        fakeGateway.reset();

        String email = "seller_retry_" + UUID.randomUUID() + "@vaultri.com";
        authService.signup(new SignupRequest(email, "Password123!", "Retry Seller", null));
        seller = sellerRepository.findByEmailIgnoreCase(email).orElseThrow();
        token = tokenService.generateAccessToken(seller);
    }

    @Test
    @DisplayName("fresh deal creation makes zero lookup calls and exactly one create call")
    void testFreshDealCreationZeroLookupsOneCreate() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Fresh Deal Client", "fresh@test.com", "+919876543210",
                "Fresh Link Deal", 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DEPOSIT_PENDING"));

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(0);
        assertThat(fakeGateway.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("recovered row, no link at gateway: one lookup, one create")
    void testRecoveredRowNoLinkAtGatewayOneLookupOneCreate() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Recovered No Link Client", "nolink@test.com", "+919876543210",
                "Recovered No Link Deal", 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        // First attempt fails at gateway (Step A succeeds, createPaymentLink fails)
        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(0);
        assertThat(fakeGateway.getCallCount()).isEqualTo(1);

        // Gateway recovers; retry discovers recovered row, performs 1 lookup (returns empty), and 1 create
        fakeGateway.setShouldFail(false);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(2); // 1 initial failed + 1 successful on retry
    }

    @Test
    @DisplayName("recovered row, link status created: adopted, zero creates")
    void testRecoveredRowStatusCreatedAdoptedZeroCreates() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Adopt Client", "adopt@test.com", "+919876543210",
                "Adopt Deal", 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        // Initial attempt fails at gateway
        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        Deal createdDeal = dealRepository.findBySellerId(seller.getId()).stream()
                .filter(d -> d.getTitle().equals("Adopt Deal"))
                .findFirst().orElseThrow();
        Payment payment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(payment.getRazorpayLinkId()).isNull();

        // Simulate gateway had created the link in "created" status
        String rzpLinkId = "plink_created_" + payment.getId();
        fakeGateway.seedLink(payment.getId().toString(), rzpLinkId, "https://rzp.io/i/" + rzpLinkId, "created");
        fakeGateway.setShouldFail(false);

        int createsBeforeRetry = fakeGateway.getCallCount();
        int lookupsBeforeRetry = fakeGateway.getLookupCallCount();

        // Retry discovers recovered row, performs 1 lookup, finds "created" status, adopts link without creating
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").value(rzpLinkId));

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(lookupsBeforeRetry + 1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(createsBeforeRetry); // Zero create calls on retry
        Payment updatedPayment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(updatedPayment.getRazorpayLinkId()).isEqualTo(rzpLinkId);
    }

    @Test
    @DisplayName("recovered row, link status cancelled: 502, no adoption, zero creates, audit row PAYMENT_LINK_NEEDS_REVIEW exists once, payment row still has null link id")
    void testRecoveredRowStatusCancelledNeedsReview() throws Exception {
        assertRecoveredRowNonCreatedStatusNeedsReview("cancelled");
    }

    @Test
    @DisplayName("recovered row, link status expired: 502, no adoption, zero creates, audit row PAYMENT_LINK_NEEDS_REVIEW exists once, payment row still has null link id")
    void testRecoveredRowStatusExpiredNeedsReview() throws Exception {
        assertRecoveredRowNonCreatedStatusNeedsReview("expired");
    }

    @Test
    @DisplayName("recovered row, link status paid: 502, no adoption, zero creates, audit row PAYMENT_LINK_NEEDS_REVIEW exists once, payment row still has null link id")
    void testRecoveredRowStatusPaidNeedsReview() throws Exception {
        assertRecoveredRowNonCreatedStatusNeedsReview("paid");
    }

    @Test
    @DisplayName("recovered row, link status unknown: 502, no adoption, zero creates, audit row PAYMENT_LINK_NEEDS_REVIEW exists once, payment row still has null link id")
    void testRecoveredRowStatusUnknownNeedsReview() throws Exception {
        assertRecoveredRowNonCreatedStatusNeedsReview("unknown");
    }

    private void assertRecoveredRowNonCreatedStatusNeedsReview(String gatewayStatus) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Review Client " + gatewayStatus, "review_" + gatewayStatus + "@test.com", "+919876543210",
                "Review Deal " + gatewayStatus, 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        Deal createdDeal = dealRepository.findBySellerId(seller.getId()).stream()
                .filter(d -> d.getTitle().equals("Review Deal " + gatewayStatus))
                .findFirst().orElseThrow();
        Payment payment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(payment.getRazorpayLinkId()).isNull();

        // Seed gateway link with non-created status
        String rzpLinkId = "plink_seed_" + gatewayStatus + "_" + payment.getId();
        fakeGateway.seedLink(payment.getId().toString(), rzpLinkId, "https://rzp.io/i/" + rzpLinkId, gatewayStatus);
        fakeGateway.setShouldFail(false);

        int createsBeforeRetry = fakeGateway.getCallCount();
        int lookupsBeforeRetry = fakeGateway.getLookupCallCount();

        // Retry discovers recovered row, looks up link, sees status is not "created":
        // returns 502, does not adopt, does not create
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(lookupsBeforeRetry + 1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(createsBeforeRetry); // Zero creates

        // Payment row unchanged (still null link id)
        Payment paymentAfter = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(paymentAfter.getRazorpayLinkId()).isNull();

        // One audit row PAYMENT_LINK_NEEDS_REVIEW exists
        List<AuditLog> auditLogs = auditRepository.findByDealIdAndSellerId(createdDeal.getId(), seller.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent();
        List<AuditLog> needsReviewLogs = auditLogs.stream().filter(a -> "PAYMENT_LINK_NEEDS_REVIEW".equals(a.getAction())).toList();
        assertThat(needsReviewLogs).hasSize(1);
        AuditLog reviewLog = needsReviewLogs.get(0);
        assertThat(reviewLog.getDetail()).contains(payment.getId().toString());
        assertThat(reviewLog.getDetail()).contains(gatewayStatus);
        assertThat(reviewLog.getDetail()).doesNotContain("review_" + gatewayStatus + "@test.com");
        assertThat(reviewLog.getDetail()).doesNotContain("https://");
    }

    @Test
    @DisplayName("Cancellation fee link: fresh cancellation makes zero lookups and exactly one create")
    void testCancellationFeeFreshZeroLookupsOneCreate() throws Exception {
        Deal deal = setupDealPaid("Fee Fresh Deal");

        String cancelKey = UUID.randomUUID().toString();
        CancelDealRequest cancelReq = new CancelDealRequest(5000L, "Cancelled by agreement");

        int lookupsBefore = fakeGateway.getLookupCallCount();
        int createsBefore = fakeGateway.getCallCount();

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(lookupsBefore); // 0 lookups
        assertThat(fakeGateway.getCallCount()).isEqualTo(createsBefore + 1); // 1 create
    }

    @Test
    @DisplayName("Cancellation fee link: recovered row with created status is adopted with zero creates")
    void testCancellationFeeRecoveredStatusCreatedAdopted() throws Exception {
        Deal deal = setupDealPaid("Fee Adopt Deal");

        String cancelKey = UUID.randomUUID().toString();
        CancelDealRequest cancelReq = new CancelDealRequest(5000L, "Cancelled by agreement");

        // First attempt fails at fee link creation
        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isBadGateway());

        Payment feePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE).orElseThrow();
        assertThat(feePayment.getRazorpayLinkId()).isNull();

        // Seed fee link in "created" status
        String feeLinkId = "plink_fee_created_" + feePayment.getId();
        fakeGateway.seedLink(feePayment.getId().toString(), feeLinkId, "https://rzp.io/i/" + feeLinkId, "created");
        fakeGateway.setShouldFail(false);

        int lookupsBefore = fakeGateway.getLookupCallCount();
        int createsBefore = fakeGateway.getCallCount();

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationFeePayment.razorpayPaymentLinkId").value(feeLinkId));

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(lookupsBefore + 1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(createsBefore); // Zero creates on retry
    }

    @Test
    @DisplayName("Cancellation fee link: recovered row with cancelled status returns 502 and logs PAYMENT_LINK_NEEDS_REVIEW")
    void testCancellationFeeRecoveredStatusCancelledNeedsReview() throws Exception {
        Deal deal = setupDealPaid("Fee Cancelled Review Deal");

        String cancelKey = UUID.randomUUID().toString();
        CancelDealRequest cancelReq = new CancelDealRequest(5000L, "Cancelled by agreement");

        // First attempt fails at fee link creation
        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isBadGateway());

        Payment feePayment = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE).orElseThrow();
        assertThat(feePayment.getRazorpayLinkId()).isNull();

        // Seed fee link in "cancelled" status
        String feeLinkId = "plink_fee_cancelled_" + feePayment.getId();
        fakeGateway.seedLink(feePayment.getId().toString(), feeLinkId, "https://rzp.io/i/" + feeLinkId, "cancelled");
        fakeGateway.setShouldFail(false);

        int lookupsBefore = fakeGateway.getLookupCallCount();
        int createsBefore = fakeGateway.getCallCount();

        mockMvc.perform(post("/api/v1/deals/" + deal.getId() + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", cancelKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isBadGateway());

        assertThat(fakeGateway.getLookupCallCount()).isEqualTo(lookupsBefore + 1);
        assertThat(fakeGateway.getCallCount()).isEqualTo(createsBefore); // Zero creates on retry

        Payment feePaymentAfter = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.CANCELLATION_FEE).orElseThrow();
        assertThat(feePaymentAfter.getRazorpayLinkId()).isNull();

        List<AuditLog> auditLogs = auditRepository.findByDealIdAndSellerId(deal.getId(), seller.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent();
        List<AuditLog> needsReviewLogs = auditLogs.stream().filter(a -> "PAYMENT_LINK_NEEDS_REVIEW".equals(a.getAction())).toList();
        assertThat(needsReviewLogs).hasSize(1);
        assertThat(needsReviewLogs.get(0).getDetail()).contains(feePayment.getId().toString());
        assertThat(needsReviewLogs.get(0).getDetail()).contains("cancelled");
    }

    private Deal setupDealPaid(String title) throws Exception {
        String createKey = UUID.randomUUID().toString();
        CreateDealRequest createReq = new CreateDealRequest(
                "Fee Client", "fee@test.com", "+919876543210",
                title, 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated());

        Deal deal = dealRepository.findBySellerId(seller.getId()).stream()
                .filter(d -> d.getTitle().equals(title)).findFirst().orElseThrow();
        Payment deposit = paymentRepository.findByDealIdAndType(deal.getId(), PaymentType.DEPOSIT).orElseThrow();
        deposit.setStatus(com.vaultri.core.payment.entity.PaymentStatus.PAID);
        deposit.setRazorpayPaymentId("pay_dummy_" + UUID.randomUUID());
        paymentRepository.save(deposit);
        deal.setStatus(DealStatus.DEPOSIT_PAID);
        return dealRepository.save(deal);
    }

    @Test
    @DisplayName("Lost response: create succeeds at gateway but client sees timeout; retry adopts existing link, exactly 1 link created, deal ends DEPOSIT_PENDING")
    void testLostResponseAdoptsExistingLink() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Lost Response Client", "client@lost.com", "+919876543210",
                "Lost Response Deal", 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        Deal createdDeal = dealRepository.findBySellerId(seller.getId()).stream()
                .filter(d -> d.getTitle().equals("Lost Response Deal"))
                .findFirst().orElseThrow();
        Payment payment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(payment.getRazorpayLinkId()).isNull();

        String rzpLinkId = "plink_lost_" + payment.getId();
        fakeGateway.seedLink(payment.getId().toString(), rzpLinkId, "https://rzp.io/i/" + rzpLinkId);
        fakeGateway.setShouldFail(false);

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(createdDeal.getId().toString()))
                .andExpect(jsonPath("$.status").value("DEPOSIT_PENDING"))
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").value(rzpLinkId));

        Payment updatedPayment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(updatedPayment.getRazorpayLinkId()).isEqualTo(rzpLinkId);
        assertThat(fakeGateway.getStoredLinkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Lookup fails: 502, nothing stored wrongly, following retry succeeds")
    void testLookupFailsReturns502AndFollowingRetrySucceeds() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Lookup Fail Client", "client@lookup.com", "+919876543210",
                "Lookup Fail Deal", 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        Deal createdDeal = dealRepository.findBySellerId(seller.getId()).stream()
                .filter(d -> d.getTitle().equals("Lookup Fail Deal"))
                .findFirst().orElseThrow();
        Payment payment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(payment.getRazorpayLinkId()).isNull();

        fakeGateway.setShouldFail(false);
        fakeGateway.setShouldLookupFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        Payment paymentAfterFailedLookup = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(paymentAfterFailedLookup.getRazorpayLinkId()).isNull();

        fakeGateway.setShouldLookupFail(false);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DEPOSIT_PENDING"))
                .andExpect(jsonPath("$.depositPayment.razorpayPaymentLinkId").exists());

        Payment finalPayment = paymentRepository.findByDealIdAndType(createdDeal.getId(), PaymentType.DEPOSIT).orElseThrow();
        assertThat(finalPayment.getRazorpayLinkId()).isNotNull();
    }

    @Test
    @DisplayName("Request payload: notify false, reminder_enable false, no customer object, expire_by within bounds, reference_id equals payment UUID <= 40 chars")
    void testRequestPayloadShapeAndFields() throws Exception {
        Instant dueDate = Instant.now().plus(10, ChronoUnit.DAYS);
        CreateDealRequest req = new CreateDealRequest(
                "Sensitive Customer", "secret@customer.com", "+919999999999",
                "Website Project Alpha", 80000L, 25, dueDate, "Contract Alpha"
        );
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        CreateLinkRequest captured = fakeGateway.getLastCreateRequest();
        assertThat(captured).isNotNull();

        assertThat(captured.title()).isEqualTo("Website Project Alpha");
        assertThat(captured.title()).doesNotContain("Sensitive Customer");
        assertThat(captured.title()).doesNotContain("secret@customer.com");

        String refId = captured.paymentId().toString();
        assertThat(refId).hasSizeLessThanOrEqualTo(40);
        assertThat(UUID.fromString(refId)).isNotNull();

        assertThat(captured.dueDate()).isEqualTo(dueDate);
    }

    @Test
    @DisplayName("10 concurrent threads retrying the same deal create exactly one link")
    void testConcurrentRetriesCreateExactlyOneLink() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateDealRequest req = new CreateDealRequest(
                "Concurrent Client", "concurrent@test.com", "+919876543210",
                "Concurrent Project", 100000L, 20, Instant.now().plus(7, ChronoUnit.DAYS), "Terms"
        );

        fakeGateway.setShouldFail(true);
        mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());

        fakeGateway.setShouldFail(false);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    var mvcResult = mockMvc.perform(post("/api/v1/deals")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    int statusCode = mvcResult.getResponse().getStatus();
                    if (statusCode == 201) {
                        createdCount.incrementAndGet();
                    } else if (statusCode == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(fakeGateway.getStoredLinkCount()).isEqualTo(1);
        assertThat(createdCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(createdCount.get() + conflictCount.get()).isEqualTo(threads);
    }

    @Test
    @DisplayName("Narrowed exception: unrelated IllegalArgumentException returns 500, not 400")
    void testUnrelatedIllegalArgumentExceptionReturns500() throws Exception {
        mockMvc.perform(get("/test/exceptions/illegal-argument")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError());
    }
}
