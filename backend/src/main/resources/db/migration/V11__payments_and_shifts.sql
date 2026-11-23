-- V11: payments, receipts and cashier shifts (task 4.3). FR-018.
-- Decisions (user, 2026-11-23; docs/19 §4): payment methods are admin-managed and start EMPTY;
-- an issued invoice may be paid in parts but never beyond its balance; payments are recorded in
-- the cashier's open shift; a shift is closed by counting cash per currency and signed off by a
-- different person; receipts use a basic, PROVISIONAL format. Payments never change: reversals
-- and refunds are task 4.4. No method names or amounts are defined here.

INSERT INTO permissions (code, description) VALUES
    ('PAYMENT_METHOD_MANAGE', 'Add, rename and deactivate payment methods'),
    ('PAYMENT_RECORD',        'Open and close your cashier shift and record payments on issued invoices'),
    ('SHIFT_REVIEW',          'Review and sign off closed cashier shifts (not your own)');

-- ---------------------------------------------------------------------------
-- Payment methods. Whether a method is cash is fixed once created (it decides the cash count).
-- ---------------------------------------------------------------------------
CREATE TABLE payment_methods (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    code             varchar(30)   NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    name             varchar(100)  NOT NULL CHECK (length(trim(name)) > 0),
    is_cash          boolean       NOT NULL,
    status           varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by       uuid          NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_methods_org_code UNIQUE (organization_id, code),
    CONSTRAINT uq_payment_methods_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_payment_methods_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id)
);

-- ---------------------------------------------------------------------------
-- Cashier shifts: OPEN → CLOSED (cash counted) → REVIEWED (signed off by someone else).
-- ---------------------------------------------------------------------------
CREATE TABLE cashier_shifts (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL,
    facility_id      uuid          NOT NULL,
    cashier_id       uuid          NOT NULL,
    status           varchar(20)   NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'REVIEWED')),
    opened_at        timestamptz   NOT NULL DEFAULT now(),
    closed_at        timestamptz,
    close_note       text,
    reviewed_by      uuid,
    reviewed_at      timestamptz,
    review_note      text,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_cashier_shifts_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_cashier_shifts_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_cashier_shifts_cashier FOREIGN KEY (organization_id, cashier_id) REFERENCES users (organization_id, id),
    CONSTRAINT fk_cashier_shifts_reviewer FOREIGN KEY (organization_id, reviewed_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_cashier_shifts_two_person CHECK (reviewed_by IS NULL OR reviewed_by <> cashier_id),
    CONSTRAINT ck_cashier_shifts_closed CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT ck_cashier_shifts_reviewed CHECK (
        (status = 'REVIEWED') = (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND review_note IS NOT NULL)
        AND (review_note IS NULL OR length(trim(review_note)) > 0))
);
-- One open shift per cashier.
CREATE UNIQUE INDEX uq_cashier_shifts_open ON cashier_shifts (cashier_id) WHERE status = 'OPEN';
CREATE INDEX ix_cashier_shifts_facility ON cashier_shifts (facility_id, status, opened_at DESC);

-- Cash counted at close, per currency. Written once when the shift closes.
CREATE TABLE shift_cash_counts (
    organization_id  uuid           NOT NULL,
    shift_id         uuid           NOT NULL,
    currency         char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    expected         numeric(19,4)  NOT NULL CHECK (expected >= 0),
    counted          numeric(19,4)  NOT NULL CHECK (counted >= 0),
    difference       numeric(19,4)  NOT NULL,
    PRIMARY KEY (shift_id, currency),
    CONSTRAINT fk_shift_cash_counts_shift FOREIGN KEY (organization_id, shift_id)
        REFERENCES cashier_shifts (organization_id, id),
    CONSTRAINT ck_shift_cash_counts_difference CHECK (difference = counted - expected)
);

-- Gapless receipt numbers per organization.
CREATE TABLE receipt_counters (
    organization_id  uuid    PRIMARY KEY REFERENCES organizations (id),
    last_number      bigint  NOT NULL CHECK (last_number >= 0)
);

-- ---------------------------------------------------------------------------
-- Payments: append-only. The idempotency key makes a retried request return the same payment.
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                uuid           PRIMARY KEY,
    organization_id   uuid           NOT NULL,
    facility_id       uuid           NOT NULL,
    invoice_id        uuid           NOT NULL,
    shift_id          uuid           NOT NULL,
    method_id         uuid           NOT NULL,
    method_is_cash    boolean        NOT NULL,
    currency          char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount            numeric(19,4)  NOT NULL CHECK (amount > 0),
    balance_after     numeric(19,4)  NOT NULL CHECK (balance_after >= 0),
    reference         varchar(100),
    receipt_number    varchar(30)    NOT NULL,
    idempotency_key   uuid           NOT NULL,
    received_by       uuid           NOT NULL,
    received_at       timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_idempotency UNIQUE (organization_id, idempotency_key),
    CONSTRAINT uq_payments_receipt UNIQUE (organization_id, receipt_number),
    CONSTRAINT fk_payments_invoice FOREIGN KEY (organization_id, invoice_id) REFERENCES invoices (organization_id, id),
    CONSTRAINT fk_payments_shift FOREIGN KEY (organization_id, shift_id) REFERENCES cashier_shifts (organization_id, id),
    CONSTRAINT fk_payments_method FOREIGN KEY (organization_id, method_id) REFERENCES payment_methods (organization_id, id),
    CONSTRAINT fk_payments_received_by FOREIGN KEY (organization_id, received_by) REFERENCES users (organization_id, id)
);
CREATE INDEX ix_payments_invoice ON payments (invoice_id, received_at);
CREATE INDEX ix_payments_shift ON payments (shift_id, received_at);

-- Payments go only on issued invoices and into an open shift.
CREATE FUNCTION app_payment_checks() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT status FROM invoices WHERE id = NEW.invoice_id) <> 'ISSUED' THEN
        RAISE EXCEPTION 'invoice % is not issued', NEW.invoice_id USING ERRCODE = 'check_violation';
    END IF;
    IF (SELECT status FROM cashier_shifts WHERE id = NEW.shift_id) <> 'OPEN' THEN
        RAISE EXCEPTION 'shift % is not open', NEW.shift_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payments_checks BEFORE INSERT ON payments
    FOR EACH ROW EXECUTE FUNCTION app_payment_checks();

-- An invoice with payments can no longer be voided (refunds and reversals are task 4.4).
CREATE FUNCTION app_invoice_paid_not_void() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'VOID' AND OLD.status <> 'VOID'
            AND EXISTS (SELECT 1 FROM payments WHERE invoice_id = OLD.id) THEN
        RAISE EXCEPTION 'invoice % has payments and cannot be voided', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_invoices_paid_not_void BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION app_invoice_paid_not_void();

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE anywhere; payments and cash counts are never updated.
-- ---------------------------------------------------------------------------
ALTER TABLE payment_methods ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_methods FORCE ROW LEVEL SECURITY;
CREATE POLICY payment_methods_isolation ON payment_methods
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE cashier_shifts ENABLE ROW LEVEL SECURITY;
ALTER TABLE cashier_shifts FORCE ROW LEVEL SECURITY;
CREATE POLICY cashier_shifts_isolation ON cashier_shifts
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE shift_cash_counts ENABLE ROW LEVEL SECURITY;
ALTER TABLE shift_cash_counts FORCE ROW LEVEL SECURITY;
CREATE POLICY shift_cash_counts_isolation ON shift_cash_counts
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE receipt_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY receipt_counters_isolation ON receipt_counters
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;
CREATE POLICY payments_isolation ON payments
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON payment_methods TO gcare_app;
GRANT UPDATE (name, status, updated_at, version) ON payment_methods TO gcare_app;
GRANT SELECT, INSERT ON cashier_shifts TO gcare_app;
GRANT UPDATE (status, closed_at, close_note, reviewed_by, reviewed_at, review_note, version) ON cashier_shifts TO gcare_app;
GRANT SELECT, INSERT ON shift_cash_counts TO gcare_app;
GRANT SELECT, INSERT ON receipt_counters TO gcare_app;
GRANT UPDATE (last_number) ON receipt_counters TO gcare_app;
GRANT SELECT, INSERT ON payments TO gcare_app;
