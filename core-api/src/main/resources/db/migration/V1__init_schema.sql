-- ============================================================================
-- Vaultri Core API - V1 Initial Database Schema
-- Primary keys: UUID (gen_random_uuid())
-- Monetary amounts: BIGINT (in paise, >= 0)
-- Timestamps: TIMESTAMPTZ
-- ============================================================================

-- Common trigger function to automatically maintain updated_at on record modifications
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ----------------------------------------------------------------------------
-- 1. Sellers Table
-- ----------------------------------------------------------------------------
CREATE TABLE sellers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    phone VARCHAR(50),
    plan VARCHAR(32) NOT NULL DEFAULT 'free',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enforces case-insensitive email uniqueness for seller authentication and lookups
CREATE UNIQUE INDEX idx_sellers_lower_email ON sellers (lower(email));

-- Automatically refresh updated_at timestamp on seller profile updates
CREATE TRIGGER trg_sellers_updated_at
BEFORE UPDATE ON sellers
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ----------------------------------------------------------------------------
-- 2. Deals Table
-- ----------------------------------------------------------------------------
CREATE TABLE deals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id UUID NOT NULL REFERENCES sellers(id) ON DELETE RESTRICT,
    customer_name VARCHAR(255) NOT NULL,
    customer_email VARCHAR(255),
    customer_phone VARCHAR(50),
    title VARCHAR(255),
    price BIGINT NOT NULL CHECK (price > 0),
    deposit_percent INT NOT NULL CHECK (deposit_percent >= 0 AND deposit_percent <= 100),
    deposit_amount BIGINT NOT NULL CHECK (deposit_amount >= 0),
    due_date TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'DEPOSIT_PENDING', 'DEPOSIT_PAID', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    contract_text TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_deals_deposit_amount_le_price CHECK (deposit_amount <= price)
);

-- Indexes foreign key to optimize joins between sellers and their deals
CREATE INDEX idx_deals_seller_id ON deals (seller_id);

-- Optimizes dashboard queries filtering deals by seller and status, ordered by newest first
CREATE INDEX idx_deals_seller_status_created ON deals (seller_id, status, created_at DESC);

-- Trigger to automatically maintain updated_at on deal modifications
CREATE TRIGGER trg_deals_updated_at
BEFORE UPDATE ON deals
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ----------------------------------------------------------------------------
-- 3. Payments Table
-- ----------------------------------------------------------------------------
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    type VARCHAR(32) NOT NULL CHECK (type IN ('DEPOSIT', 'CANCELLATION_FEE')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('CREATED', 'PAID', 'EXPIRED', 'CANCELLED')),
    razorpay_link_id VARCHAR(255) UNIQUE,
    razorpay_payment_id VARCHAR(255) UNIQUE,
    short_url TEXT,
    cancel_requested_at TIMESTAMPTZ,
    cancel_attempts INT NOT NULL DEFAULT 0 CHECK (cancel_attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payments_paid_requires_payment_id CHECK (
        (status = 'PAID' AND razorpay_payment_id IS NOT NULL) OR
        (status <> 'PAID' AND razorpay_payment_id IS NULL)
    )
);

-- Indexes foreign key to optimize queries finding all payments associated with a specific deal
CREATE INDEX idx_payments_deal_id ON payments (deal_id);

-- Ensures a deal can never have two active payments of the same type simultaneously
CREATE UNIQUE INDEX idx_payments_active_deal_type ON payments (deal_id, type) WHERE status IN ('CREATED', 'PAID');

-- Trigger to automatically maintain updated_at on payment modifications
CREATE TRIGGER trg_payments_updated_at
BEFORE UPDATE ON payments
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ----------------------------------------------------------------------------
-- 4. Ledger Entries Table (Append-only)
-- ----------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    seller_id UUID NOT NULL REFERENCES sellers(id) ON DELETE RESTRICT,
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    type VARCHAR(32) NOT NULL CHECK (type IN ('DEPOSIT', 'CANCELLATION_FEE')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_payment_type UNIQUE (payment_id, type)
);

-- Indexes foreign key to query immutable ledger transaction history for a deal
CREATE INDEX idx_ledger_entries_deal_id ON ledger_entries (deal_id);

-- Indexes foreign key to query total settled ledger balance across all deals for a seller
CREATE INDEX idx_ledger_entries_seller_id ON ledger_entries (seller_id);

-- Indexes foreign key to verify ledger entry creation per payment transaction
CREATE INDEX idx_ledger_entries_payment_id ON ledger_entries (payment_id);

-- ----------------------------------------------------------------------------
-- 5. Audit Logs Table (Append-only)
-- ----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    action VARCHAR(100) NOT NULL,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes foreign key to retrieve chronological audit trail of state transitions for a deal
CREATE INDEX idx_audit_logs_deal_id ON audit_logs (deal_id);

-- ----------------------------------------------------------------------------
-- Immutability Function and Triggers for Ledger Entries and Audit Logs
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: updates, deletes, and truncates are prohibited', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_modification();

CREATE TRIGGER trg_ledger_entries_truncate
BEFORE TRUNCATE ON ledger_entries
FOR EACH STATEMENT EXECUTE FUNCTION prevent_modification();

CREATE TRIGGER trg_audit_logs_immutable
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION prevent_modification();

CREATE TRIGGER trg_audit_logs_truncate
BEFORE TRUNCATE ON audit_logs
FOR EACH STATEMENT EXECUTE FUNCTION prevent_modification();

-- ----------------------------------------------------------------------------
-- 6. Webhook Events Table
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    razorpay_event_id VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

-- Optimizes background polling to quickly locate unprocessed inbound webhook events
CREATE INDEX idx_webhook_events_unprocessed ON webhook_events (processed_at) WHERE processed_at IS NULL;

-- ----------------------------------------------------------------------------
-- 7. Idempotency Keys Table
-- ----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id UUID NOT NULL REFERENCES sellers(id) ON DELETE CASCADE,
    deal_id UUID REFERENCES deals(id) ON DELETE CASCADE,
    key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(255) NOT NULL,
    response_status INT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_seller_key UNIQUE (seller_id, key)
);

-- Indexes foreign key to support cascading operations and seller-level idempotency key lookups
CREATE INDEX idx_idempotency_keys_seller_id ON idempotency_keys (seller_id);
CREATE INDEX idx_idempotency_keys_deal_id ON idempotency_keys (deal_id);

-- ----------------------------------------------------------------------------
-- 8. Reminders Table
-- ----------------------------------------------------------------------------
CREATE TABLE reminders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    channel VARCHAR(32) NOT NULL,
    message TEXT,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    due_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes foreign key to fetch all reminder attempts for a specific deal
CREATE INDEX idx_reminders_deal_id ON reminders (deal_id);

-- Optimizes background scheduler queries that poll for due pending reminders to dispatch
CREATE INDEX idx_reminders_status_due_at ON reminders (status, due_at);

-- ----------------------------------------------------------------------------
-- 9. Refresh Tokens Table
-- ----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id UUID NOT NULL REFERENCES sellers(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes foreign key to optimize queries finding or revoking active refresh tokens for a seller
CREATE INDEX idx_refresh_tokens_seller_id ON refresh_tokens (seller_id);

-- ----------------------------------------------------------------------------
-- 10. ShedLock Table
-- ----------------------------------------------------------------------------
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

