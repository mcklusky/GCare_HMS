-- V12: refunds (payment reversals) and fee exemptions with two-person approval (task 4.4).
-- FR-018, FR-019. Decisions (user, 2026-11-23; docs/19 §5): a refund is requested with a reason
-- and approved by a different person, then paid out by a cashier in an open shift (cash refunds
-- reduce that shift's expected cash); the original payment never changes. An exemption waives a
-- stated amount up to the balance, with an admin-managed reason (empty by default) and approval
-- by a different person. No eligibility rules or amounts are defined here.

INSERT INTO permissions (code, description) VALUES
    ('REFUND_REQUEST',           'Request a refund of a payment, and pay out approved refunds in your shift'),
    ('REFUND_APPROVE',           'Approve or reject refund requests (not your own)'),
    ('EXEMPTION_REQUEST',        'Request a fee exemption on an issued invoice'),
    ('EXEMPTION_APPROVE',        'Approve or reject exemption requests (not your own)'),
    ('EXEMPTION_REASON_MANAGE',  'Add, rename and deactivate exemption reasons');

-- ---------------------------------------------------------------------------
-- Exemption reasons: admin-managed, none by default.
-- ---------------------------------------------------------------------------
CREATE TABLE exemption_reasons (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    code             varchar(30)   NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    name             varchar(200)  NOT NULL CHECK (length(trim(name)) > 0),
    status           varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by       uuid          NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_exemption_reasons_org_code UNIQUE (organization_id, code),
    CONSTRAINT uq_exemption_reasons_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_exemption_reasons_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id)
);

-- ---------------------------------------------------------------------------
-- Exemptions: REQUESTED → APPROVED | REJECTED. Decided rows never change.
-- ---------------------------------------------------------------------------
CREATE TABLE exemptions (
    id               uuid           PRIMARY KEY,
    organization_id  uuid           NOT NULL,
    facility_id      uuid           NOT NULL,
    invoice_id       uuid           NOT NULL,
    reason_id        uuid           NOT NULL,
    currency         char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount           numeric(19,4)  NOT NULL CHECK (amount > 0),
    note             text           NOT NULL CHECK (length(trim(note)) > 0),
    status           varchar(20)    NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by     uuid           NOT NULL,
    requested_at     timestamptz    NOT NULL DEFAULT now(),
    decided_by       uuid,
    decided_at       timestamptz,
    decision_note    text,
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_exemptions_invoice FOREIGN KEY (organization_id, invoice_id) REFERENCES invoices (organization_id, id),
    CONSTRAINT fk_exemptions_reason FOREIGN KEY (organization_id, reason_id) REFERENCES exemption_reasons (organization_id, id),
    CONSTRAINT fk_exemptions_requested_by FOREIGN KEY (organization_id, requested_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_exemptions_decided_by FOREIGN KEY (organization_id, decided_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_exemptions_two_person CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_exemptions_decision CHECK (
        (status = 'REQUESTED' AND decided_by IS NULL AND decided_at IS NULL AND decision_note IS NULL)
        OR (status <> 'REQUESTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0))
);
CREATE INDEX ix_exemptions_invoice ON exemptions (invoice_id, requested_at);
CREATE INDEX ix_exemptions_pending ON exemptions (facility_id, status, requested_at);

-- ---------------------------------------------------------------------------
-- Refunds of a payment: REQUESTED → APPROVED → PAID, or REJECTED. The payment itself never
-- changes. Paid refunds take a receipt number from the same sequence as payments.
-- ---------------------------------------------------------------------------
-- Unique constraint so refunds can reference payments by (organization_id, id).
ALTER TABLE payments ADD CONSTRAINT uq_payments_org_id UNIQUE (organization_id, id);

CREATE TABLE refunds (
    id               uuid           PRIMARY KEY,
    organization_id  uuid           NOT NULL,
    facility_id      uuid           NOT NULL,
    invoice_id       uuid           NOT NULL,
    payment_id       uuid           NOT NULL,
    method_id        uuid           NOT NULL,
    method_is_cash   boolean        NOT NULL,
    currency         char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount           numeric(19,4)  NOT NULL CHECK (amount > 0),
    reason           text           NOT NULL CHECK (length(trim(reason)) > 0),
    status           varchar(20)    NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'PAID')),
    requested_by     uuid           NOT NULL,
    requested_at     timestamptz    NOT NULL DEFAULT now(),
    decided_by       uuid,
    decided_at       timestamptz,
    decision_note    text,
    shift_id         uuid,
    paid_by          uuid,
    paid_at          timestamptz,
    receipt_number   varchar(30),
    payout_key       uuid,
    balance_after    numeric(19,4)  CHECK (balance_after >= 0),
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_refunds_receipt UNIQUE (organization_id, receipt_number),
    CONSTRAINT fk_refunds_invoice FOREIGN KEY (organization_id, invoice_id) REFERENCES invoices (organization_id, id),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (organization_id, payment_id) REFERENCES payments (organization_id, id),
    CONSTRAINT fk_refunds_method FOREIGN KEY (organization_id, method_id) REFERENCES payment_methods (organization_id, id),
    CONSTRAINT fk_refunds_shift FOREIGN KEY (organization_id, shift_id) REFERENCES cashier_shifts (organization_id, id),
    CONSTRAINT fk_refunds_requested_by FOREIGN KEY (organization_id, requested_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_refunds_decided_by FOREIGN KEY (organization_id, decided_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_refunds_paid_by FOREIGN KEY (organization_id, paid_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_refunds_two_person CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_refunds_decision CHECK (
        (status = 'REQUESTED' AND decided_by IS NULL AND decided_at IS NULL AND decision_note IS NULL)
        OR (status <> 'REQUESTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0)),
    CONSTRAINT ck_refunds_paid CHECK (
        (status = 'PAID') = (shift_id IS NOT NULL AND paid_by IS NOT NULL AND paid_at IS NOT NULL
            AND receipt_number IS NOT NULL AND payout_key IS NOT NULL AND balance_after IS NOT NULL))
);
CREATE INDEX ix_refunds_invoice ON refunds (invoice_id, requested_at);
CREATE INDEX ix_refunds_payment ON refunds (payment_id);
CREATE INDEX ix_refunds_pending ON refunds (facility_id, status, requested_at);
CREATE INDEX ix_refunds_shift ON refunds (shift_id);

-- Decided exemptions never change; refunds move only forward and a paid refund never changes.
CREATE FUNCTION app_exemption_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'exemption % is % and cannot be changed', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_exemptions_immutable BEFORE UPDATE ON exemptions
    FOR EACH ROW EXECUTE FUNCTION app_exemption_immutable();

CREATE FUNCTION app_refund_forward_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('REJECTED', 'PAID')
            OR (OLD.status = 'APPROVED' AND NEW.status <> 'PAID')
            OR (OLD.status = 'APPROVED' AND (NEW.amount <> OLD.amount OR NEW.decided_by IS DISTINCT FROM OLD.decided_by)) THEN
        RAISE EXCEPTION 'refund % is % and cannot change this way', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'PAID' AND (SELECT status FROM cashier_shifts WHERE id = NEW.shift_id) <> 'OPEN' THEN
        RAISE EXCEPTION 'shift % is not open', NEW.shift_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_refunds_forward_only BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION app_refund_forward_only();

-- An invoice with an approved exemption can no longer be voided either.
CREATE OR REPLACE FUNCTION app_invoice_paid_not_void() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'VOID' AND OLD.status <> 'VOID'
            AND (EXISTS (SELECT 1 FROM payments WHERE invoice_id = OLD.id)
                 OR EXISTS (SELECT 1 FROM exemptions WHERE invoice_id = OLD.id AND status = 'APPROVED')) THEN
        RAISE EXCEPTION 'invoice % has payments or exemptions and cannot be voided', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE anywhere.
-- ---------------------------------------------------------------------------
ALTER TABLE exemption_reasons ENABLE ROW LEVEL SECURITY;
ALTER TABLE exemption_reasons FORCE ROW LEVEL SECURITY;
CREATE POLICY exemption_reasons_isolation ON exemption_reasons
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE exemptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE exemptions FORCE ROW LEVEL SECURITY;
CREATE POLICY exemptions_isolation ON exemptions
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY refunds_isolation ON refunds
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON exemption_reasons TO gcare_app;
GRANT UPDATE (name, status, updated_at, version) ON exemption_reasons TO gcare_app;
GRANT SELECT, INSERT ON exemptions TO gcare_app;
GRANT UPDATE (status, decided_by, decided_at, decision_note, version) ON exemptions TO gcare_app;
GRANT SELECT, INSERT ON refunds TO gcare_app;
GRANT UPDATE (status, decided_by, decided_at, decision_note, shift_id, paid_by, paid_at, receipt_number,
    payout_key, balance_after, version) ON refunds TO gcare_app;
