package com.vaultri.core.deal.service;

import com.vaultri.core.audit.service.AuditService;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.dto.CreateDealCommand;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.exception.DealNotFoundException;
import com.vaultri.core.deal.exception.IllegalDealTransitionException;
import com.vaultri.core.deal.policy.CancellationFeePolicy;
import com.vaultri.core.deal.repository.DealRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    @Mock
    private DealRepository dealRepository;

    @Mock
    private SellerRepository sellerRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private CancellationFeePolicy cancellationFeePolicy;

    @InjectMocks
    private DealService dealService;

    private UUID sellerId;
    private UUID otherSellerId;
    private UUID dealId;
    private Seller seller;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        otherSellerId = UUID.randomUUID();
        dealId = UUID.randomUUID();

        seller = Seller.builder()
                .id(sellerId)
                .email("seller@vaultri.com")
                .passwordHash("hashed_pw")
                .name("Seller Name")
                .build();
    }

    @Test
    @DisplayName("createDraft validates price > 0")
    void testCreateDraftRejectsZeroOrNegativePrice() {
        CreateDealCommand commandZero = CreateDealCommand.builder()
                .customerName("Customer")
                .price(0L)
                .depositPercent(20)
                .build();

        assertThatThrownBy(() -> dealService.createDraft(sellerId, commandZero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Price must be greater than zero");

        CreateDealCommand commandNegative = CreateDealCommand.builder()
                .customerName("Customer")
                .price(-5000L)
                .depositPercent(20)
                .build();

        assertThatThrownBy(() -> dealService.createDraft(sellerId, commandNegative))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Price must be greater than zero");
    }

    @Test
    @DisplayName("createDraft validates 0 <= depositPercent <= 100")
    void testCreateDraftRejectsInvalidDepositPercent() {
        CreateDealCommand commandTooHigh = CreateDealCommand.builder()
                .customerName("Customer")
                .price(100000L)
                .depositPercent(101)
                .build();

        assertThatThrownBy(() -> dealService.createDraft(sellerId, commandTooHigh))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deposit percent must be between 0 and 100");

        CreateDealCommand commandTooLow = CreateDealCommand.builder()
                .customerName("Customer")
                .price(100000L)
                .depositPercent(-1)
                .build();

        assertThatThrownBy(() -> dealService.createDraft(sellerId, commandTooLow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deposit percent must be between 0 and 100");
    }

    @Test
    @DisplayName("createDraft computes deposit_amount with integer math in paise (half-up rounding) and logs audit")
    void testCreateDraftComputesDepositAmountAndAudits() {
        when(sellerRepository.findById(sellerId)).thenReturn(Optional.of(seller));
        when(dealRepository.save(any(Deal.class))).thenAnswer(invocation -> {
            Deal d = invocation.getArgument(0);
            d.setId(dealId);
            return d;
        });

        // Test 1: exact division (100,000 paise * 25% = 25,000 paise)
        CreateDealCommand cmd1 = CreateDealCommand.builder()
                .customerName("Alice")
                .customerEmail("alice@test.com")
                .title("Design Work")
                .price(100000L)
                .depositPercent(25)
                .dueDate(Instant.now().plusSeconds(86400))
                .contractText("Terms")
                .build();

        Deal deal1 = dealService.createDraft(sellerId, cmd1);
        assertThat(deal1.getDepositAmount()).isEqualTo(25000L);
        assertThat(deal1.getStatus()).isEqualTo(DealStatus.DRAFT);
        verify(auditService).logAction(eq(deal1), eq("DEAL_CREATED"), anyString());

        // Test 2: half-up rounding (100,005 paise * 33% = 3,300,165. (3300165 + 50)/100 = 33,002 paise)
        CreateDealCommand cmd2 = CreateDealCommand.builder()
                .customerName("Bob")
                .price(100005L)
                .depositPercent(33)
                .build();

        Deal deal2 = dealService.createDraft(sellerId, cmd2);
        assertThat(deal2.getDepositAmount()).isEqualTo(33002L);
    }

    @Test
    @DisplayName("Cross-seller isolation: requesting a deal owned by another seller throws DealNotFoundException")
    void testCrossSellerAccessThrowsDealNotFound() {
        when(dealRepository.findByIdAndSellerId(dealId, otherSellerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.markDepositPending(dealId, otherSellerId))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage("Deal not found: " + dealId);

        assertThatThrownBy(() -> dealService.markDepositPaid(dealId, otherSellerId))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage("Deal not found: " + dealId);

        assertThatThrownBy(() -> dealService.startProgress(dealId, otherSellerId))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage("Deal not found: " + dealId);

        assertThatThrownBy(() -> dealService.complete(dealId, otherSellerId))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage("Deal not found: " + dealId);

        assertThatThrownBy(() -> dealService.cancel(dealId, otherSellerId, 0L))
                .isInstanceOf(DealNotFoundException.class)
                .hasMessage("Deal not found: " + dealId);

        // Verify findById was never invoked directly
        verify(dealRepository, never()).findById(any());
        verify(auditService, never()).logAction(any(), any(), any());
    }

    @Test
    @DisplayName("markDepositPending transitions DRAFT to DEPOSIT_PENDING and audits")
    void testMarkDepositPendingSuccess() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.DRAFT)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));

        Deal updated = dealService.markDepositPending(dealId, sellerId);

        assertThat(updated.getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);
        verify(auditService).logAction(eq(updated), eq("DEPOSIT_PENDING"), anyString());
    }

    @Test
    @DisplayName("markDepositPaid transitions DEPOSIT_PENDING to DEPOSIT_PAID and audits")
    void testMarkDepositPaidSuccess() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.DEPOSIT_PENDING)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));

        Deal updated = dealService.markDepositPaid(dealId, sellerId);

        assertThat(updated.getStatus()).isEqualTo(DealStatus.DEPOSIT_PAID);
        verify(auditService).logAction(eq(updated), eq("DEPOSIT_PAID"), anyString());
    }

    @Test
    @DisplayName("startProgress transitions DEPOSIT_PAID to IN_PROGRESS and audits")
    void testStartProgressSuccess() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.DEPOSIT_PAID)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));

        Deal updated = dealService.startProgress(dealId, sellerId);

        assertThat(updated.getStatus()).isEqualTo(DealStatus.IN_PROGRESS);
        verify(auditService).logAction(eq(updated), eq("IN_PROGRESS"), anyString());
    }

    @Test
    @DisplayName("complete transitions IN_PROGRESS to COMPLETED and audits")
    void testCompleteSuccess() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.IN_PROGRESS)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));

        Deal updated = dealService.complete(dealId, sellerId);

        assertThat(updated.getStatus()).isEqualTo(DealStatus.COMPLETED);
        verify(auditService).logAction(eq(updated), eq("COMPLETED"), anyString());
    }

    @Test
    @DisplayName("cancel transitions to CANCELLED, validates fee policy, and audits")
    void testCancelSuccessWithFee() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.IN_PROGRESS)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));

        Deal updated = dealService.cancel(dealId, sellerId, 25000L);

        assertThat(updated.getStatus()).isEqualTo(DealStatus.CANCELLED);
        verify(cancellationFeePolicy).validateFee(deal, 25000L);
        verify(auditService).logAction(eq(updated), eq("CANCELLED"), contains("25000"));
    }

    @Test
    @DisplayName("Illegal transition throws IllegalDealTransitionException and writes no audit log")
    void testIllegalTransitionFailsWithoutAudit() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.DRAFT)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));

        // Attempting to complete a DRAFT deal is illegal
        assertThatThrownBy(() -> dealService.complete(dealId, sellerId))
                .isInstanceOf(IllegalDealTransitionException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("COMPLETED");

        verify(dealRepository, never()).save(any());
        verify(auditService, never()).logAction(any(), any(), any());
    }

    @Test
    @DisplayName("OptimisticLockingFailureException propagates without being swallowed")
    void testOptimisticLockingPropagates() {
        Deal deal = Deal.builder()
                .id(dealId)
                .seller(seller)
                .status(DealStatus.IN_PROGRESS)
                .price(100000L)
                .build();
        when(dealRepository.findByIdAndSellerId(dealId, sellerId)).thenReturn(Optional.of(deal));
        when(dealRepository.save(any(Deal.class))).thenThrow(new OptimisticLockingFailureException("Stale version"));

        assertThatThrownBy(() -> dealService.complete(dealId, sellerId))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
