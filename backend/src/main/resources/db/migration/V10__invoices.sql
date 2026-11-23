-- V10: visit invoices with decimal totals and a price snapshot per line (task 4.2). FR-017.
-- Decisions (user, 2026-11-23; docs/19 §3): charges come from orders linked to a billable service
-- plus lines added by a cashier; the cashier picks one currency per invoice, never converted;
-- tax is computed per line and rounded half-up to the currency's decimals; an issued invoice never
-- changes: it is voided with a reason and a new invoice is made. No amounts are defined here.

INSERT INTO permissions (code, description) VALUES
    ('INVOICE_READ',   'See visit invoices'),
    ('INVOICE_MANAGE', 'Start invoices, add and remove charges, and issue invoices'),
    ('INVOICE_VOID',   'Void an issued invoice with a reason');

-- Gapless invoice numbers per organization, taken when an invoice is issued.
CREATE TABLE invoice_counters (
    organization_id  uuid    PRIMARY KEY REFERENCES organizations (id),
    last_number      bigint  NOT NULL CHECK (last_number >= 0)
);

-- ---------------------------------------------------------------------------
-- Invoices: one per visit at a time (DRAFT or ISSUED). Totals are fixed when issued.
-- ---------------------------------------------------------------------------
CREATE TABLE invoices (
    id                   uuid           PRIMARY KEY,
    organization_id      uuid           NOT NULL,
    facility_id          uuid           NOT NULL,
    encounter_id         uuid           NOT NULL,
    patient_id           uuid           NOT NULL,
    currency             char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status               varchar(20)    NOT NULL CHECK (status IN ('DRAFT', 'ISSUED', 'VOID')),
    invoice_number       varchar(30),
    subtotal             numeric(19,4),
    tax_total            numeric(19,4),
    total                numeric(19,4),
    replaces_invoice_id  uuid,
    created_by           uuid           NOT NULL,
    created_at           timestamptz    NOT NULL DEFAULT now(),
    issued_by            uuid,
    issued_at            timestamptz,
    void_reason          text,
    voided_by            uuid,
    voided_at            timestamptz,
    updated_at           timestamptz    NOT NULL DEFAULT now(),
    version              bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoices_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_invoices_number UNIQUE (organization_id, invoice_number),
    CONSTRAINT fk_invoices_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES encounters (organization_id, id),
    CONSTRAINT fk_invoices_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_invoices_replaces FOREIGN KEY (organization_id, replaces_invoice_id)
        REFERENCES invoices (organization_id, id),
    CONSTRAINT fk_invoices_created_by FOREIGN KEY (organization_id, created_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_invoices_issued_by FOREIGN KEY (organization_id, issued_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_invoices_voided_by FOREIGN KEY (organization_id, voided_by) REFERENCES users (organization_id, id),
    -- Issuing sets the number, issuer and totals together. A VOID invoice may be a discarded draft
    -- (never issued, no number) or a voided issued invoice.
    CONSTRAINT ck_invoices_issued CHECK (
        (invoice_number IS NULL) = (issued_by IS NULL)
        AND (invoice_number IS NULL) = (issued_at IS NULL)
        AND (invoice_number IS NULL) = (total IS NULL)
        AND (total IS NULL) = (subtotal IS NULL)
        AND (total IS NULL) = (tax_total IS NULL)
        AND (total IS NULL OR (subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total))
        AND (status <> 'DRAFT' OR invoice_number IS NULL)
        AND (status <> 'ISSUED' OR invoice_number IS NOT NULL)),
    CONSTRAINT ck_invoices_void CHECK (
        (status = 'VOID') = (void_reason IS NOT NULL AND voided_by IS NOT NULL AND voided_at IS NOT NULL)
        AND (void_reason IS NULL OR length(trim(void_reason)) > 0))
);
CREATE UNIQUE INDEX uq_invoices_live_per_visit ON invoices (encounter_id) WHERE status IN ('DRAFT', 'ISSUED');
CREATE INDEX ix_invoices_facility ON invoices (facility_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- Lines: a copy of the service, approved price and tax rate in effect when the line was added.
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_lines (
    id                uuid           PRIMARY KEY,
    organization_id   uuid           NOT NULL,
    invoice_id        uuid           NOT NULL,
    source            varchar(20)    NOT NULL CHECK (source IN ('ORDER', 'MANUAL')),
    order_id          uuid,
    service_id        uuid           NOT NULL,
    service_code      varchar(50)    NOT NULL,
    service_name      varchar(200)   NOT NULL,
    price_id          uuid           NOT NULL,
    quantity          integer        NOT NULL CHECK (quantity BETWEEN 1 AND 999),
    unit_price        numeric(19,4)  NOT NULL CHECK (unit_price >= 0),
    tax_rate_code     varchar(30),
    tax_rate_percent  numeric(7,4),
    net_amount        numeric(19,4)  NOT NULL,
    tax_amount        numeric(19,4)  NOT NULL CHECK (tax_amount >= 0),
    line_total        numeric(19,4)  NOT NULL,
    status            varchar(20)    NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED')),
    reason            text,
    added_by          uuid           NOT NULL,
    added_at          timestamptz    NOT NULL DEFAULT now(),
    removed_reason    text,
    removed_by        uuid,
    removed_at        timestamptz,
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (organization_id, invoice_id)
        REFERENCES invoices (organization_id, id),
    CONSTRAINT fk_invoice_lines_order FOREIGN KEY (organization_id, order_id)
        REFERENCES clinical_orders (organization_id, id),
    CONSTRAINT fk_invoice_lines_service FOREIGN KEY (organization_id, service_id)
        REFERENCES billable_services (organization_id, id),
    CONSTRAINT fk_invoice_lines_price FOREIGN KEY (organization_id, price_id)
        REFERENCES service_prices (organization_id, id),
    CONSTRAINT fk_invoice_lines_added_by FOREIGN KEY (organization_id, added_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_invoice_lines_removed_by FOREIGN KEY (organization_id, removed_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_invoice_lines_source CHECK (
        (source = 'ORDER' AND order_id IS NOT NULL)
        OR (source = 'MANUAL' AND order_id IS NULL AND reason IS NOT NULL AND length(trim(reason)) > 0)),
    CONSTRAINT ck_invoice_lines_tax CHECK ((tax_rate_code IS NULL) = (tax_rate_percent IS NULL)
        AND (tax_rate_code IS NOT NULL OR tax_amount = 0)),
    CONSTRAINT ck_invoice_lines_amounts CHECK (
        net_amount = unit_price * quantity AND line_total = net_amount + tax_amount),
    CONSTRAINT ck_invoice_lines_removed CHECK (
        (status = 'REMOVED') = (removed_reason IS NOT NULL AND removed_by IS NOT NULL AND removed_at IS NOT NULL)
        AND (removed_reason IS NULL OR length(trim(removed_reason)) > 0))
);
-- An order is charged at most once per invoice (a removed order line stays as the record).
CREATE UNIQUE INDEX uq_invoice_lines_order ON invoice_lines (invoice_id, order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_invoice_lines_invoice ON invoice_lines (invoice_id, added_at);

-- An issued invoice can only be voided; a void invoice never changes.
CREATE FUNCTION app_invoice_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'VOID' THEN
        RAISE EXCEPTION 'invoice % is void and cannot be changed', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ISSUED' AND (NEW.status <> 'VOID'
            OR NEW.invoice_number IS DISTINCT FROM OLD.invoice_number
            OR NEW.subtotal IS DISTINCT FROM OLD.subtotal
            OR NEW.tax_total IS DISTINCT FROM OLD.tax_total
            OR NEW.total IS DISTINCT FROM OLD.total
            OR NEW.issued_by IS DISTINCT FROM OLD.issued_by
            OR NEW.issued_at IS DISTINCT FROM OLD.issued_at) THEN
        RAISE EXCEPTION 'invoice % is issued; it can only be voided', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_invoices_immutable BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION app_invoice_immutable();

-- Lines change only while their invoice is a draft; a removed line never changes again.
CREATE FUNCTION app_invoice_line_draft_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT status FROM invoices WHERE id = NEW.invoice_id) <> 'DRAFT' THEN
        RAISE EXCEPTION 'invoice % is not a draft; its lines cannot change', NEW.invoice_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'REMOVED' THEN
        RAISE EXCEPTION 'invoice line % was removed and cannot be changed', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_invoice_lines_draft_only BEFORE INSERT OR UPDATE ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION app_invoice_line_draft_only();

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE anywhere.
-- ---------------------------------------------------------------------------
ALTER TABLE invoice_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY invoice_counters_isolation ON invoice_counters
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;
CREATE POLICY invoices_isolation ON invoices
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE invoice_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY invoice_lines_isolation ON invoice_lines
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON invoice_counters TO gcare_app;
GRANT UPDATE (last_number) ON invoice_counters TO gcare_app;
GRANT SELECT, INSERT ON invoices TO gcare_app;
GRANT UPDATE (status, invoice_number, subtotal, tax_total, total, issued_by, issued_at,
    void_reason, voided_by, voided_at, updated_at, version) ON invoices TO gcare_app;
GRANT SELECT, INSERT ON invoice_lines TO gcare_app;
GRANT UPDATE (status, removed_reason, removed_by, removed_at) ON invoice_lines TO gcare_app;
