-- V9: billable services, dated prices and tax rates with two-person approval (task 4.1). FR-012.
-- Decisions (user, 2026-11-23): prices are entered in the app and start EMPTY; any ISO 4217
-- currency, stated on every price, never converted; tax is a configurable rate, empty by default
-- (OD-11); a different person must approve every price and tax rate.
-- Approved prices and rates never change: a change is a new dated row. No values are defined here.

INSERT INTO permissions (code, description) VALUES
    ('BILLING_READ',           'See billable services, prices and tax rates'),
    ('BILLING_CATALOG_MANAGE', 'Add, rename and deactivate billable services'),
    ('PRICE_PROPOSE',          'Propose new prices and tax rates'),
    ('PRICE_APPROVE',          'Approve or reject proposed prices and tax rates (not your own)');

-- ---------------------------------------------------------------------------
-- What can be charged. Optionally linked to an orderable item (used by invoicing later).
-- ---------------------------------------------------------------------------
CREATE TABLE billable_services (
    id                     uuid          PRIMARY KEY,
    organization_id        uuid          NOT NULL REFERENCES organizations (id),
    code                   varchar(50)   NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    name                   varchar(200)  NOT NULL CHECK (length(trim(name)) > 0),
    order_catalog_item_id  uuid,
    status                 varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by             uuid          NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    version                bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_billable_services_org_code UNIQUE (organization_id, code),
    CONSTRAINT uq_billable_services_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_billable_services_item FOREIGN KEY (organization_id, order_catalog_item_id)
        REFERENCES order_catalog_items (organization_id, id),
    CONSTRAINT fk_billable_services_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id)
);
-- One billable service per orderable item.
CREATE UNIQUE INDEX uq_billable_services_item ON billable_services (order_catalog_item_id)
    WHERE order_catalog_item_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Tax rates (OD-11): identified by code, versioned by effective date. None exist by default.
-- ---------------------------------------------------------------------------
CREATE TABLE tax_rates (
    id               uuid           PRIMARY KEY,
    organization_id  uuid           NOT NULL REFERENCES organizations (id),
    code             varchar(30)    NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    name             varchar(200)   NOT NULL CHECK (length(trim(name)) > 0),
    rate_percent     numeric(7,4)   NOT NULL CHECK (rate_percent >= 0 AND rate_percent <= 100),
    effective_from   date           NOT NULL,
    status           varchar(20)    NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    reason           text           NOT NULL CHECK (length(trim(reason)) > 0),
    proposed_by      uuid           NOT NULL,
    proposed_at      timestamptz    NOT NULL DEFAULT now(),
    decided_by       uuid,
    decided_at       timestamptz,
    decision_note    text,
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_tax_rates_proposed_by FOREIGN KEY (organization_id, proposed_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_tax_rates_decided_by FOREIGN KEY (organization_id, decided_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_tax_rates_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT ck_tax_rates_decision CHECK (
        (status = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL AND decision_note IS NULL)
        OR (status <> 'PROPOSED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0))
);
CREATE UNIQUE INDEX uq_tax_rates_live ON tax_rates (organization_id, code, effective_from)
    WHERE status IN ('PROPOSED', 'APPROVED');

-- ---------------------------------------------------------------------------
-- Prices: one currency each, versioned by effective date. Amount precision is checked against
-- the currency's minor units by the application; the database keeps up to 4 decimals.
-- ---------------------------------------------------------------------------
CREATE TABLE service_prices (
    id               uuid           PRIMARY KEY,
    organization_id  uuid           NOT NULL,
    service_id       uuid           NOT NULL,
    currency         char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount           numeric(19,4)  NOT NULL CHECK (amount >= 0),
    tax_rate_code    varchar(30),
    effective_from   date           NOT NULL,
    status           varchar(20)    NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    reason           text           NOT NULL CHECK (length(trim(reason)) > 0),
    proposed_by      uuid           NOT NULL,
    proposed_at      timestamptz    NOT NULL DEFAULT now(),
    decided_by       uuid,
    decided_at       timestamptz,
    decision_note    text,
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT uq_service_prices_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_service_prices_service FOREIGN KEY (organization_id, service_id)
        REFERENCES billable_services (organization_id, id),
    CONSTRAINT fk_service_prices_proposed_by FOREIGN KEY (organization_id, proposed_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_service_prices_decided_by FOREIGN KEY (organization_id, decided_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_service_prices_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT ck_service_prices_decision CHECK (
        (status = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL AND decision_note IS NULL)
        OR (status <> 'PROPOSED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0))
);
CREATE UNIQUE INDEX uq_service_prices_live ON service_prices (service_id, currency, effective_from)
    WHERE status IN ('PROPOSED', 'APPROVED');
CREATE INDEX ix_service_prices_lookup ON service_prices (service_id, currency, status, effective_from DESC);

-- Decided rows never change again.
CREATE FUNCTION app_decided_row_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION '% % is % and cannot be changed', TG_TABLE_NAME, OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_service_prices_immutable BEFORE UPDATE ON service_prices
    FOR EACH ROW EXECUTE FUNCTION app_decided_row_immutable();
CREATE TRIGGER trg_tax_rates_immutable BEFORE UPDATE ON tax_rates
    FOR EACH ROW EXECUTE FUNCTION app_decided_row_immutable();

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE. Prices and rates: only the decision columns can change.
-- ---------------------------------------------------------------------------
ALTER TABLE billable_services ENABLE ROW LEVEL SECURITY;
ALTER TABLE billable_services FORCE ROW LEVEL SECURITY;
CREATE POLICY billable_services_isolation ON billable_services
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE tax_rates ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_rates FORCE ROW LEVEL SECURITY;
CREATE POLICY tax_rates_isolation ON tax_rates
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE service_prices ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_prices FORCE ROW LEVEL SECURITY;
CREATE POLICY service_prices_isolation ON service_prices
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON billable_services TO gcare_app;
GRANT UPDATE (name, order_catalog_item_id, status, updated_at, version) ON billable_services TO gcare_app;
GRANT SELECT, INSERT ON tax_rates TO gcare_app;
GRANT UPDATE (status, decided_by, decided_at, decision_note, version) ON tax_rates TO gcare_app;
GRANT SELECT, INSERT ON service_prices TO gcare_app;
GRANT UPDATE (status, decided_by, decided_at, decision_note, version) ON service_prices TO gcare_app;
