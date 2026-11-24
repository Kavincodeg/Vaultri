package com.vaultri.core;

import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DealOptimisticLockingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("7. Optimistic locking: concurrent updates to the same deal fail with ObjectOptimisticLockingFailureException")
    void testOptimisticLockingThrowsException() {
        TransactionTemplate txRequiresNew = new TransactionTemplate(transactionManager);
        txRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // 1. Persist initial seller and deal
        UUID dealId = txRequiresNew.execute(status -> {
            Seller seller = sellerRepository.save(Seller.builder()
                    .email("seller_opt_" + UUID.randomUUID() + "@vaultri.com")
                    .passwordHash("hashed_pw_bcrypt")
                    .name("Opt Seller")
                    .plan("pro")
                    .build());

            Deal deal = dealRepository.save(Deal.builder()
                    .seller(seller)
                    .customerName("Opt Customer")
                    .customerEmail("opt@customer.com")
                    .price(100000L)
                    .depositPercent(10)
                    .depositAmount(10000L)
                    .status(DealStatus.DRAFT)
                    .build());
            return deal.getId();
        });

        assertThat(dealId).isNotNull();

        // 2. Load the same deal in two separate transactions
        Deal dealTx1 = txRequiresNew.execute(status -> dealRepository.findById(dealId).orElseThrow());
        Deal dealTx2 = txRequiresNew.execute(status -> dealRepository.findById(dealId).orElseThrow());

        assertThat(dealTx1).isNotNull();
        assertThat(dealTx2).isNotNull();
        assertThat(dealTx1.getVersion()).isEqualTo(0L);
        assertThat(dealTx2.getVersion()).isEqualTo(0L);

        // 3. Update and commit dealTx1 (version in database advances to 1)
        txRequiresNew.execute(status -> {
            dealTx1.setTitle("Updated by TX 1");
            return dealRepository.saveAndFlush(dealTx1);
        });

        // 4. Attempt to update and commit dealTx2 (still holds stale version 0) -> must fail
        assertThatThrownBy(() -> txRequiresNew.execute(status -> {
            dealTx2.setTitle("Updated by TX 2");
            return dealRepository.saveAndFlush(dealTx2);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
