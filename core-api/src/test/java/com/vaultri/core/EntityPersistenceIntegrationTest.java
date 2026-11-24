package com.vaultri.core;

import com.vaultri.core.audit.entity.AuditLog;
import com.vaultri.core.audit.repository.AuditRepository;
import com.vaultri.core.auth.entity.IdempotencyKey;
import com.vaultri.core.auth.entity.Seller;
import com.vaultri.core.auth.repository.IdempotencyKeyRepository;
import com.vaultri.core.auth.repository.SellerRepository;
import com.vaultri.core.deal.entity.Deal;
import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.deal.repository.DealRepository;
import com.vaultri.core.ledger.entity.LedgerEntry;
import com.vaultri.core.ledger.repository.LedgerRepository;
import com.vaultri.core.payment.entity.Payment;
import com.vaultri.core.payment.entity.PaymentStatus;
import com.vaultri.core.payment.entity.PaymentType;
import com.vaultri.core.payment.repository.PaymentRepository;
import com.vaultri.core.reminder.entity.Reminder;
import com.vaultri.core.reminder.entity.ReminderStatus;
import com.vaultri.core.reminder.repository.ReminderRepository;
import com.vaultri.core.webhook.entity.WebhookEvent;
import com.vaultri.core.webhook.repository.WebhookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EntityPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private DealRepository dealRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @Test
    @Transactional
    @DisplayName("6. Save and find round trip for every entity, verifying mapping and enums")
    void testSaveAndFindRoundTripForAllEntities() {
        // 1. Seller
        Seller seller = Seller.builder()
                .email("kavin_" + UUID.randomUUID() + "@vaultri.com")
                .name("Kavin")
                .passwordHash("hashed_pw_bcrypt")
                .phone("+919876543210")
                .plan("pro")
                .build();
        Seller savedSeller = sellerRepository.save(seller);
        assertThat(savedSeller.getId()).isNotNull();

        Optional<Seller> foundSeller = sellerRepository.findById(savedSeller.getId());
        assertThat(foundSeller).isPresent();
        assertThat(foundSeller.get().getEmail()).isEqualTo(seller.getEmail());
        assertThat(foundSeller.get().getPlan()).isEqualTo("pro");
        assertThat(foundSeller.get().getPhone()).isEqualTo("+919876543210");

        // 2. Deal (with enum DealStatus)
        Deal deal = Deal.builder()
                .seller(savedSeller)
                .customerName("Bob")
                .customerEmail("bob@customer.com")
                .customerPhone("+919876543210")
                .title("Website Redesign Contract")
                .price(200000L) // 2,000 INR in paise
                .depositPercent(25)
                .depositAmount(50000L)
                .dueDate(Instant.now().plus(14, ChronoUnit.DAYS))
                .status(DealStatus.DEPOSIT_PENDING)
                .contractText("Standard Vaultri Protection Agreement")
                .build();
        Deal savedDeal = dealRepository.save(deal);
        assertThat(savedDeal.getId()).isNotNull();
        assertThat(savedDeal.getVersion()).isEqualTo(0L);

        Optional<Deal> foundDeal = dealRepository.findById(savedDeal.getId());
        assertThat(foundDeal).isPresent();
        assertThat(foundDeal.get().getStatus()).isEqualTo(DealStatus.DEPOSIT_PENDING);
        assertThat(foundDeal.get().getPrice()).isEqualTo(200000L);
        assertThat(foundDeal.get().getDepositPercent()).isEqualTo(25);

        // 3. Payment (with enums PaymentType, PaymentStatus)
        String linkId = "plink_" + UUID.randomUUID().toString().replace("-", "");
        Payment payment = Payment.builder()
                .deal(savedDeal)
                .type(PaymentType.DEPOSIT)
                .amount(50000L)
                .status(PaymentStatus.CREATED)
                .razorpayLinkId(linkId)
                .shortUrl("https://rzp.io/i/" + linkId)
                .build();
        Payment savedPayment = paymentRepository.save(payment);
        assertThat(savedPayment.getId()).isNotNull();

        Optional<Payment> foundPayment = paymentRepository.findById(savedPayment.getId());
        assertThat(foundPayment).isPresent();
        assertThat(foundPayment.get().getType()).isEqualTo(PaymentType.DEPOSIT);
        assertThat(foundPayment.get().getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(foundPayment.get().getRazorpayLinkId()).isEqualTo(linkId);

        // 4. LedgerEntry (with enum PaymentType, append-only)
        LedgerEntry ledgerEntry = LedgerEntry.builder()
                .deal(savedDeal)
                .seller(savedSeller)
                .payment(savedPayment)
                .type(PaymentType.DEPOSIT)
                .amount(50000L)
                .build();
        LedgerEntry savedLedger = ledgerRepository.save(ledgerEntry);
        assertThat(savedLedger.getId()).isNotNull();

        Optional<LedgerEntry> foundLedger = ledgerRepository.findById(savedLedger.getId());
        assertThat(foundLedger).isPresent();
        assertThat(foundLedger.get().getType()).isEqualTo(PaymentType.DEPOSIT);
        assertThat(foundLedger.get().getAmount()).isEqualTo(50000L);

        // 5. AuditLog (append-only)
        AuditLog auditLog = AuditLog.builder()
                .deal(savedDeal)
                .action("DEAL_CREATED")
                .detail("Deal was initiated with status DEPOSIT_PENDING")
                .build();
        AuditLog savedAudit = auditRepository.save(auditLog);
        assertThat(savedAudit.getId()).isNotNull();

        Optional<AuditLog> foundAudit = auditRepository.findById(savedAudit.getId());
        assertThat(foundAudit).isPresent();
        assertThat(foundAudit.get().getAction()).isEqualTo("DEAL_CREATED");

        // 6. WebhookEvent
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        WebhookEvent webhookEvent = WebhookEvent.builder()
                .razorpayEventId(eventId)
                .eventType("payment_link.paid")
                .payload("{\"event\":\"payment_link.paid\",\"id\":\"" + eventId + "\"}")
                .build();
        WebhookEvent savedWebhook = webhookRepository.save(webhookEvent);
        assertThat(savedWebhook.getId()).isNotNull();

        Optional<WebhookEvent> foundWebhook = webhookRepository.findById(savedWebhook.getId());
        assertThat(foundWebhook).isPresent();
        assertThat(foundWebhook.get().getRazorpayEventId()).isEqualTo(eventId);
        assertThat(foundWebhook.get().getEventType()).isEqualTo("payment_link.paid");

        // 7. IdempotencyKey
        String idemKey = "idem_" + UUID.randomUUID();
        IdempotencyKey idempotencyKey = IdempotencyKey.builder()
                .seller(savedSeller)
                .key(idemKey)
                .requestHash("sha256_mock_hash")
                .responseStatus(200)
                .responseBody("{\"status\":\"ok\"}")
                .build();
        IdempotencyKey savedIdem = idempotencyKeyRepository.save(idempotencyKey);
        assertThat(savedIdem.getId()).isNotNull();

        Optional<IdempotencyKey> foundIdem = idempotencyKeyRepository.findById(savedIdem.getId());
        assertThat(foundIdem).isPresent();
        assertThat(foundIdem.get().getKey()).isEqualTo(idemKey);
        assertThat(foundIdem.get().getResponseStatus()).isEqualTo(200);

        // 8. Reminder (with enum ReminderStatus)
        Reminder reminder = Reminder.builder()
                .deal(savedDeal)
                .channel("email")
                .message("Friendly reminder to pay deposit")
                .status(ReminderStatus.PENDING)
                .attempts(0)
                .dueAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        Reminder savedReminder = reminderRepository.save(reminder);
        assertThat(savedReminder.getId()).isNotNull();

        Optional<Reminder> foundReminder = reminderRepository.findById(savedReminder.getId());
        assertThat(foundReminder).isPresent();
        assertThat(foundReminder.get().getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(foundReminder.get().getChannel()).isEqualTo("email");
    }
}
