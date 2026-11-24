package com.vaultri.core.deal.service;

import com.vaultri.core.AbstractIntegrationTest;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.dto.CreateDealCommand;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.DealNotFoundException;
import com.vaultri.core.deal.exception.IllegalDealTransitionException;
import com.vaultri.core.deal.exception.InvalidCancellationFeeException;
import com.vaultri.core.deal.repository.DealRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DealServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DealService dealService;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Seller sellerA;
    private Seller sellerB;

    @BeforeEach
    void setUp() {
        sellerA = sellerRepository.save(Seller.builder()
                .email("seller_a_" + UUID.randomUUID() + "@vaultri.com")
                .passwordHash("hash_secret")
                .name("Seller Alpha")
                .plan("pro")
                .build());

        sellerB = sellerRepository.save(Seller.builder()
                .email("seller_b_" + UUID.randomUUID() + "@vaultri.com")
                .passwordHash("hash_secret")
                .name("Seller Beta")
                .plan("pro")
                .build());
    }

    @Test
    @DisplayName("a. Seller A cannot read, transition, or cancel seller B's deal: throws DealNotFoundException matching nonexistent ID")
    void testCrossSellerAccessThrowsDealNotFound() {
        Deal dealB = dealService.createDraft(sellerB.getId(), CreateDealCommand.builder()
                .customerName("Customer of B")
                .price(100000L)
                .depositPercent(20)
                .build());

        UUID nonexistentId = UUID.randomUUID();
        String expectedNonexistentMsg = "Deal not found: " + nonexistentId;
        String expectedDealBMsg = "Deal not found: " + dealB.getId();

        // Baseline: verify nonexistent ID message
        assertThatThrownBy(() -> dealService.markDepositPending(nonexistentId, sellerA.getId()))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage(expectedNonexistentMsg);

        // Seller A trying to markDepositPending on Seller B's deal
        assertThatThrownBy(() -> dealService.markDepositPending(dealB.getId(), sellerA.getId()))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage(expectedDealBMsg);

        // Seller A trying to markDepositPaid on Seller B's deal
        assertThatThrownBy(() -> dealService.markDepositPaid(dealB.getId(), sellerA.getId()))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage(expectedDealBMsg);

        // Seller A trying to startProgress on Seller B's deal
        assertThatThrownBy(() -> dealService.startProgress(dealB.getId(), sellerA.getId()))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage(expectedDealBMsg);

        // Seller A trying to complete Seller B's deal
        assertThatThrownBy(() -> dealService.complete(dealB.getId(), sellerA.getId()))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage(expectedDealBMsg);

        // Seller A trying to cancel Seller B's deal
        assertThatThrownBy(() -> dealService.cancel(dealB.getId(), sellerA.getId(), 0L))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage(expectedDealBMsg);
    }

    @Test
    @DisplayName("b. Every successful transition writes exactly one audit_logs row; query the table to prove it")
    void testSuccessfulTransitionsWriteAuditLogs() {
        Deal deal = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                .customerName("Audit Customer")
                .price(150000L)
                .depositPercent(25)
                .build());

        UUID dealId = deal.getId();

        // 1. After createDraft: exactly 1 audit row (DEAL_CREATED)
        Integer count1 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(count1).isEqualTo(1);

        // 2. After markDepositPending: exactly 2 audit rows (DEPOSIT_PENDING added)
        dealService.markDepositPending(dealId, sellerA.getId());
        Integer count2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(count2).isEqualTo(2);

        // 3. After markDepositPaid: exactly 3 audit rows (DEPOSIT_PAID added)
        dealService.markDepositPaid(dealId, sellerA.getId());
        Integer count3 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(count3).isEqualTo(3);

        // 4. After startProgress: exactly 4 audit rows (IN_PROGRESS added)
        dealService.startProgress(dealId, sellerA.getId());
        Integer count4 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(count4).isEqualTo(4);

        // 5. After complete: exactly 5 audit rows (COMPLETED added)
        dealService.complete(dealId, sellerA.getId());
        Integer count5 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(count5).isEqualTo(5);
    }

    @Test
    @DisplayName("c. A failed transition (illegal, or fee above max) changes nothing: deal status unchanged and no audit row")
    void testFailedTransitionChangesNothing() {
        Deal deal = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                .customerName("Fail Test Customer")
                .price(100000L)
                .depositPercent(20)
                .build());

        UUID dealId = deal.getId();

        // Initial state: DRAFT, 1 audit row (DEAL_CREATED)
        Integer auditCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(auditCountBefore).isEqualTo(1);

        // 1. Illegal transition: DRAFT -> COMPLETED
        assertThatThrownBy(() -> dealService.complete(dealId, sellerA.getId()))
                .isInstanceOf(IllegalDealTransitionException.class);

        // Assert deal status unchanged
        String statusAfterIllegal = jdbcTemplate.queryForObject(
                "SELECT status FROM deals WHERE id = ?",
                String.class,
                dealId
        );
        assertThat(statusAfterIllegal).isEqualTo("DRAFT");

        // Assert audit log count unchanged
        Integer auditCountAfterIllegal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(auditCountAfterIllegal).isEqualTo(1);

        // Advance deal legally to DEPOSIT_PAID (price 100,000, 50% max fee = 50,000 paise)
        dealService.markDepositPending(dealId, sellerA.getId());
        dealService.markDepositPaid(dealId, sellerA.getId());

        Integer auditCountCommitted = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(auditCountCommitted).isEqualTo(3);

        // 2. Cancellation fee above max (60,000 > 50,000 max)
        assertThatThrownBy(() -> dealService.cancel(dealId, sellerA.getId(), 60000L))
                .isInstanceOf(InvalidCancellationFeeException.class);

        // Assert status unchanged (still DEPOSIT_PAID)
        String statusAfterBadFee = jdbcTemplate.queryForObject(
                "SELECT status FROM deals WHERE id = ?",
                String.class,
                dealId
        );
        assertThat(statusAfterBadFee).isEqualTo("DEPOSIT_PAID");

        // Assert no CANCELLED audit log created
        Integer auditCountAfterBadFee = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE deal_id = ?",
                Integer.class,
                dealId
        );
        assertThat(auditCountAfterBadFee).isEqualTo(3);
    }

    @Test
    @DisplayName("d. Concurrency: two threads calling complete() on the same IN_PROGRESS deal (repeated 20 times)")
    void testConcurrentCompleteRepeated20Times() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Deal deal = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                    .customerName("Concurrent Customer " + i)
                    .price(100000L)
                    .depositPercent(20)
                    .build());

            UUID currentDealId = deal.getId();
            dealService.markDepositPending(currentDealId, sellerA.getId());
            dealService.markDepositPaid(currentDealId, sellerA.getId());
            dealService.startProgress(currentDealId, sellerA.getId());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            Runnable task = () -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    dealService.complete(currentDealId, sellerA.getId());
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException | IllegalDealTransitionException ex) {
                    failureCount.incrementAndGet();
                } catch (Exception ex) {
                    // unexpected exception
                    ex.printStackTrace();
                }
            };

            executor.submit(task);
            executor.submit(task);

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            executor.shutdown();
            boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            assertThat(terminated).isTrue();

            assertThat(successCount.get())
                    .as("Iteration " + i + ": exactly one thread must succeed")
                    .isEqualTo(1);
            assertThat(failureCount.get())
                    .as("Iteration " + i + ": exactly one thread must fail")
                    .isEqualTo(1);

            // Final status in DB is COMPLETED
            String finalStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM deals WHERE id = ?",
                    String.class,
                    currentDealId
            );
            assertThat(finalStatus).isEqualTo("COMPLETED");

            // Exactly one COMPLETED audit log row exists
            Integer completedAuditCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE deal_id = ? AND action = 'COMPLETED'",
                    Integer.class,
                    currentDealId
            );
            assertThat(completedAuditCount).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("e. createDraft with price 99999 and 33 percent stores expected deposit_amount, plus awkward values")
    void testCreateDraftDepositAmountCalculation() {
        // 1. price 99999, percent 33 -> 99999 * 33 = 3,299,967. (3299967 + 50) / 100 = 33000 paise
        Deal deal1 = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                .customerName("Awkward 1")
                .price(99999L)
                .depositPercent(33)
                .build());

        Long stored1 = jdbcTemplate.queryForObject(
                "SELECT deposit_amount FROM deals WHERE id = ?",
                Long.class,
                deal1.getId()
        );
        assertThat(stored1).isEqualTo(33000L);

        // 2. price 1 paise, percent 50 -> 1 * 50 = 50. (50 + 50) / 100 = 1 paise
        Deal deal2 = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                .customerName("Awkward 2")
                .price(1L)
                .depositPercent(50)
                .build());

        Long stored2 = jdbcTemplate.queryForObject(
                "SELECT deposit_amount FROM deals WHERE id = ?",
                Long.class,
                deal2.getId()
        );
        assertThat(stored2).isEqualTo(1L);

        // 3. price 1234567, percent 17 -> 1234567 * 17 = 20,987,639. (20987639 + 50) / 100 = 209876 paise
        Deal deal3 = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                .customerName("Awkward 3")
                .price(1234567L)
                .depositPercent(17)
                .build());

        Long stored3 = jdbcTemplate.queryForObject(
                "SELECT deposit_amount FROM deals WHERE id = ?",
                Long.class,
                deal3.getId()
        );
        assertThat(stored3).isEqualTo(209876L);
    }

    @Test
    @DisplayName("f. Cancellation through service with valid fee and excessive fee against real database")
    void testCancellationWithValidAndExcessiveFee() {
        Deal deal = dealService.createDraft(sellerA.getId(), CreateDealCommand.builder()
                .customerName("Cancel Customer")
                .price(200000L) // 2,000 INR -> 50% max fee = 100,000 paise
                .depositPercent(25)
                .build());

        UUID dealId = deal.getId();
        dealService.markDepositPending(dealId, sellerA.getId());
        dealService.markDepositPaid(dealId, sellerA.getId());
        dealService.startProgress(dealId, sellerA.getId());

        // 1. Excessive fee: 100,001 paise (exceeds max 100,000 paise)
        assertThatThrownBy(() -> dealService.cancel(dealId, sellerA.getId(), 100001L))
                .isInstanceOf(InvalidCancellationFeeException.class);

        // Assert deal remains IN_PROGRESS
        String statusMidway = jdbcTemplate.queryForObject(
                "SELECT status FROM deals WHERE id = ?",
                String.class,
                dealId
        );
        assertThat(statusMidway).isEqualTo("IN_PROGRESS");

        // 2. Valid fee: 75,000 paise
        Deal cancelledDeal = dealService.cancel(dealId, sellerA.getId(), 75000L);
        assertThat(cancelledDeal.getStatus()).isEqualTo(DealStatus.CANCELLED);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM deals WHERE id = ?",
                String.class,
                dealId
        );
        assertThat(finalStatus).isEqualTo("CANCELLED");

        String cancelAuditDetail = jdbcTemplate.queryForObject(
                "SELECT detail FROM audit_logs WHERE deal_id = ? AND action = 'CANCELLED'",
                String.class,
                dealId
        );
        assertThat(cancelAuditDetail).contains("75000");
    }
}
