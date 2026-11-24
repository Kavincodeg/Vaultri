package com.vaultri.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SchemaMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID sellerId;
    private UUID dealId;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sellers (id, email, password_hash, name, plan) VALUES (?, ?, ?, ?, ?)",
                sellerId, "seller_" + sellerId + "@vaultri.com", "hash_secret", "Test Seller", "pro"
        );

        dealId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                dealId, sellerId, "Customer One", "customer@test.com", 150000L, 20, 30000L, "DRAFT"
        );
    }

    @Test
    @DisplayName("1. Flyway migrations applied successfully on database startup")
    void testFlywayMigrationsApplied() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN " +
                "('sellers', 'deals', 'payments', 'reminders', 'audit_logs', 'ledger_entries', 'webhook_events', 'idempotency_keys')",
                Integer.class
        );
        assertThat(tableCount).isEqualTo(8);
    }

    @Test
    @DisplayName("2. Inserting a deal with an invalid status is rejected by check constraint")
    void testInvalidDealStatusRejected() {
        UUID invalidDealId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                invalidDealId, sellerId, "Invalid Deal", "inv@test.com", 100000L, 20, 20000L, "INVALID_STATUS"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("deals_status_check");
    }

    @Test
    @DisplayName("3. Inserting a deal with deposit_percent > 100 or price <= 0 is rejected by check constraint")
    void testInvalidDepositPercentOrPriceRejected() {
        // deposit_percent 101
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sellerId, "Bad Percent", "bad@test.com", 100000L, 101, 20000L, "DRAFT"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("deals_deposit_percent_check");

        // price = 0
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sellerId, "Zero Price", "zero@test.com", 0L, 20, 0L, "DRAFT"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("deals_price_check");
    }

    @Test
    @DisplayName("4. Updating or deleting a ledger entry or audit log fails via database triggers")
    void testImmutabilityTriggersOnLedgerAndAuditLogs() {
        // Test Ledger immutability
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status) VALUES (?, ?, ?, ?, ?)",
                paymentId, dealId, "DEPOSIT", 30000L, "CREATED"
        );

        UUID ledgerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, deal_id, seller_id, payment_id, type, amount) VALUES (?, ?, ?, ?, ?, ?)",
                ledgerId, dealId, sellerId, paymentId, "DEPOSIT", 30000L
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = ? WHERE id = ?",
                50000L, ledgerId
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("is append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE id = ?",
                ledgerId
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("is append-only");

        // Test Audit Log immutability
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, deal_id, action, detail) VALUES (?, ?, ?, ?)",
                auditId, dealId, "DEAL_CREATED", "Deal created for testing"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_logs SET detail = ? WHERE id = ?",
                "Hacked detail", auditId
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("is append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE id = ?",
                auditId
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("is append-only");
    }

    @Test
    @DisplayName("5. Duplicate unique constraints rejected (razorpay_event_id, idempotency (seller_id, key), razorpay_link_id)")
    void testUniqueConstraintsRejected() {
        // 5a. Duplicate razorpay_event_id
        String eventId = "evt_" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO webhook_events (id, razorpay_event_id, event_type) VALUES (?, ?, ?)",
                UUID.randomUUID(), eventId, "payment_link.paid"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO webhook_events (id, razorpay_event_id, event_type) VALUES (?, ?, ?)",
                UUID.randomUUID(), eventId, "payment_link.paid"
        )).isInstanceOf(DataAccessException.class);

        // 5b. Duplicate idempotency (seller_id, key)
        String idemKey = "key_" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO idempotency_keys (id, seller_id, key, request_hash) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), sellerId, idemKey, "hash_123"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO idempotency_keys (id, seller_id, key, request_hash) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), sellerId, idemKey, "hash_456"
        )).isInstanceOf(DataAccessException.class);

        // 5c. Duplicate razorpay_link_id
        String linkId = "plink_" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, "DEPOSIT", 30000L, "CREATED", linkId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, "DEPOSIT", 30000L, "CREATED", linkId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("6. Duplicate ledger entry for the same payment is rejected via UNIQUE (payment_id, type)")
    void testDuplicateLedgerEntryForSamePaymentRejected() {
        UUID paymentId = UUID.randomUUID();
        String linkId = "plink_" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id) VALUES (?, ?, ?, ?, ?, ?)",
                paymentId, dealId, "DEPOSIT", 30000L, "CREATED", linkId
        );

        // First ledger entry for this payment
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, deal_id, seller_id, payment_id, type, amount) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, sellerId, paymentId, "DEPOSIT", 30000L
        );

        // Duplicate ledger entry for same payment and type must fail
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, deal_id, seller_id, payment_id, type, amount) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, sellerId, paymentId, "DEPOSIT", 30000L
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("uq_ledger_payment_type");
    }

    @Test
    @DisplayName("7. Emails differing only by case are rejected as duplicates via idx_sellers_lower_email")
    void testCaseInsensitiveEmailUniquenessRejected() {
        String baseEmail = "unique_" + UUID.randomUUID() + "@vaultri.com";
        jdbcTemplate.update(
                "INSERT INTO sellers (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), baseEmail.toLowerCase(), "hash_123", "Lower Seller"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO sellers (id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), baseEmail.toUpperCase(), "hash_123", "Upper Seller"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("idx_sellers_lower_email");
    }

    @Test
    @DisplayName("8. Deleting a deal that has ledger entries is rejected via ON DELETE RESTRICT")
    void testDeleteDealWithLedgerEntriesRejected() {
        UUID restrictDealId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                restrictDealId, sellerId, "Cust", "cust@test.com", 100000L, 20, 20000L, "DRAFT"
        );

        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status) VALUES (?, ?, ?, ?, ?)",
                paymentId, restrictDealId, "DEPOSIT", 20000L, "CREATED"
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, deal_id, seller_id, payment_id, type, amount) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), restrictDealId, sellerId, paymentId, "DEPOSIT", 20000L
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM deals WHERE id = ?",
                restrictDealId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("9. A PAID payment without razorpay_payment_id is rejected by check constraint")
    void testPaidPaymentWithoutRazorpayPaymentIdRejected() {
        // Status PAID without razorpay_payment_id -> must fail
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id, razorpay_payment_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, "DEPOSIT", 30000L, "PAID", "plink_" + UUID.randomUUID(), null
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("chk_payments_paid_requires_payment_id");

        // Status CREATED with razorpay_payment_id -> must fail
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id, razorpay_payment_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, "DEPOSIT", 30000L, "CREATED", "plink_" + UUID.randomUUID(), "pay_" + UUID.randomUUID()
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("chk_payments_paid_requires_payment_id");

        // Status PAID with razorpay_payment_id -> succeeds
        UUID validPaymentId = UUID.randomUUID();
        int rows = jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id, razorpay_payment_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                validPaymentId, dealId, "DEPOSIT", 30000L, "PAID", "plink_" + UUID.randomUUID(), "pay_" + UUID.randomUUID()
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("10. deals.updated_at changes automatically on every update via database trigger")
    void testDealsUpdatedAtChangesOnUpdate() throws InterruptedException {
        UUID testDealId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                testDealId, sellerId, "Cust", "cust@test.com", 100000L, 20, 20000L, "DRAFT"
        );

        Timestamp initialUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM deals WHERE id = ?",
                Timestamp.class,
                testDealId
        );
        assertThat(initialUpdatedAt).isNotNull();

        Thread.sleep(50);

        jdbcTemplate.update(
                "UPDATE deals SET title = 'Updated Title' WHERE id = ?",
                testDealId
        );

        Timestamp newUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM deals WHERE id = ?",
                Timestamp.class,
                testDealId
        );
        assertThat(newUpdatedAt).isNotNull();
        assertThat(newUpdatedAt).isAfter(initialUpdatedAt);
    }

    @Test
    @DisplayName("11. Inserting a payment with a NULL razorpay_link_id succeeds and two rows do not conflict")
    void testPaymentWithNullRazorpayLinkIdAllowedAndNoConflict() {
        UUID payId1 = UUID.randomUUID();
        UUID payId2 = UUID.randomUUID();

        // One DEPOSIT and one CANCELLATION_FEE for the same deal with null razorpay_link_id
        int rows1 = jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id) VALUES (?, ?, ?, ?, ?, ?)",
                payId1, dealId, "DEPOSIT", 10000L, "CREATED", null
        );
        int rows2 = jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status, razorpay_link_id) VALUES (?, ?, ?, ?, ?, ?)",
                payId2, dealId, "CANCELLATION_FEE", 5000L, "CREATED", null
        );

        assertThat(rows1).isEqualTo(1);
        assertThat(rows2).isEqualTo(1);

        String link1 = jdbcTemplate.queryForObject("SELECT razorpay_link_id FROM payments WHERE id = ?", String.class, payId1);
        String link2 = jdbcTemplate.queryForObject("SELECT razorpay_link_id FROM payments WHERE id = ?", String.class, payId2);
        assertThat(link1).isNull();
        assertThat(link2).isNull();
    }

    @Test
    @DisplayName("12. Deal with NULL customer_email saves; ledger entry with NULL payment_id is rejected")
    void testDealNullCustomerEmailAndLedgerNullPaymentId() {
        // Deal with NULL customer_email saves
        UUID nullEmailDealId = UUID.randomUUID();
        int dealRows = jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                nullEmailDealId, sellerId, "No Email Customer", null, 100000L, 20, 20000L, "DRAFT"
        );
        assertThat(dealRows).isEqualTo(1);

        // Ledger entry with NULL payment_id is rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, deal_id, seller_id, payment_id, type, amount) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), dealId, sellerId, null, "DEPOSIT", 20000L
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("payment_id");
    }

    @Test
    @DisplayName("13. Active payment uniqueness per (deal_id, type) enforced for CREATED/PAID, allowed once EXPIRED")
    void testActivePaymentUniquePerDealAndType() {
        UUID payId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status) VALUES (?, ?, ?, ?, ?)",
                payId1, dealId, "DEPOSIT", 30000L, "CREATED"
        );

        // Second CREATED deposit payment for the same deal must be rejected
        UUID payId2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status) VALUES (?, ?, ?, ?, ?)",
                payId2, dealId, "DEPOSIT", 30000L, "CREATED"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("idx_payments_active_deal_type");

        // Transition first payment to EXPIRED
        jdbcTemplate.update("UPDATE payments SET status = 'EXPIRED' WHERE id = ?", payId1);

        // Now creating a new CREATED deposit payment succeeds
        UUID payId3 = UUID.randomUUID();
        int rows = jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status) VALUES (?, ?, ?, ?, ?)",
                payId3, dealId, "DEPOSIT", 30000L, "CREATED"
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("14. deposit_amount > price is rejected by check constraint")
    void testDepositAmountGreaterThanPriceRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO deals (id, seller_id, customer_name, customer_email, price, deposit_percent, deposit_amount, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sellerId, "Cust", "cust@test.com", 100000L, 50, 150000L, "DRAFT"
        )).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("chk_deals_deposit_amount_le_price");
    }

    @Test
    @DisplayName("15. TRUNCATE on ledger_entries and audit_logs fails via append-only triggers")
    void testTruncateOnLedgerEntriesAndAuditLogsFails() {
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE ledger_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is append-only");

        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE audit_logs"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is append-only");
    }

    @Test
    @DisplayName("16. sellers.updated_at and payments.updated_at change automatically on update via database triggers")
    void testSellersAndPaymentsUpdatedAtChangesOnUpdate() throws InterruptedException {
        // 1. sellers.updated_at
        UUID testSellerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sellers (id, email, password_hash, name, plan) VALUES (?, ?, ?, ?, ?)",
                testSellerId, "update_" + testSellerId + "@vaultri.com", "hash_pw", "Initial Name", "free"
        );

        Timestamp initialSellerUpdated = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM sellers WHERE id = ?",
                Timestamp.class,
                testSellerId
        );
        assertThat(initialSellerUpdated).isNotNull();

        Thread.sleep(50);

        jdbcTemplate.update("UPDATE sellers SET name = 'Updated Name' WHERE id = ?", testSellerId);

        Timestamp newSellerUpdated = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM sellers WHERE id = ?",
                Timestamp.class,
                testSellerId
        );
        assertThat(newSellerUpdated).isNotNull();
        assertThat(newSellerUpdated).isAfter(initialSellerUpdated);

        // 2. payments.updated_at
        UUID testPaymentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payments (id, deal_id, type, amount, status) VALUES (?, ?, ?, ?, ?)",
                testPaymentId, dealId, "CANCELLATION_FEE", 10000L, "CREATED"
        );

        Timestamp initialPaymentUpdated = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM payments WHERE id = ?",
                Timestamp.class,
                testPaymentId
        );
        assertThat(initialPaymentUpdated).isNotNull();

        Thread.sleep(50);

        jdbcTemplate.update("UPDATE payments SET short_url = 'https://rzp.io/i/new' WHERE id = ?", testPaymentId);

        Timestamp newPaymentUpdated = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM payments WHERE id = ?",
                Timestamp.class,
                testPaymentId
        );
        assertThat(newPaymentUpdated).isNotNull();
        assertThat(newPaymentUpdated).isAfter(initialPaymentUpdated);
    }
}
