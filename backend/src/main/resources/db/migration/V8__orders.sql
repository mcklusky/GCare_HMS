-- V8: order catalog, clinical orders and referrals (task 3.3). FR-009.
-- Decision (user, 2026-11-23): the catalog is managed by administrators and starts EMPTY. No test,
-- imaging study or referral destination is defined here; the local demo seed adds fictional items.
-- Orders keep a snapshot of the catalog item, so later catalog edits never change an order.

INSERT INTO permissions (code, description) VALUES
    ('ORDER_CATALOG_MANAGE', 'Add, rename and deactivate orderable items'),
    ('ORDER_READ',           'See orders and department worklists at facilities in scope'),
    ('ORDER_CREATE',         'Place and cancel orders and referrals for a visit'),
    ('ORDER_PROCESS',        'Start, complete or cancel orders in a performing department');

-- ---------------------------------------------------------------------------
-- What can be ordered. Code and category are fixed once created; never deleted, only deactivated.
-- ---------------------------------------------------------------------------
CREATE TABLE order_catalog_items (
    id                        uuid          PRIMARY KEY,
    organization_id           uuid          NOT NULL REFERENCES organizations (id),
    code                      varchar(50)   NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]*$'),
    name                      varchar(200)  NOT NULL CHECK (length(trim(name)) > 0),
    category                  varchar(20)   NOT NULL CHECK (category IN ('LAB', 'IMAGING', 'PROCEDURE', 'REFERRAL')),
    performing_department_id  uuid          REFERENCES departments (id),
    status                    varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by                uuid          NOT NULL,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    version                   bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_catalog_org_code UNIQUE (organization_id, code),
    CONSTRAINT uq_order_catalog_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_order_catalog_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id)
);

-- ---------------------------------------------------------------------------
-- Orders for a visit, with a snapshot of the item. Status changes go to order_events.
-- ---------------------------------------------------------------------------
CREATE TABLE clinical_orders (
    id                        uuid          PRIMARY KEY,
    organization_id           uuid          NOT NULL,
    encounter_id              uuid          NOT NULL,
    patient_id                uuid          NOT NULL,
    facility_id               uuid          NOT NULL,
    catalog_item_id           uuid          NOT NULL,
    item_code                 varchar(50)   NOT NULL,
    item_name                 varchar(200)  NOT NULL,
    item_category             varchar(20)   NOT NULL,
    performing_department_id  uuid          REFERENCES departments (id),
    priority                  varchar(20)   NOT NULL CHECK (priority IN ('ROUTINE', 'URGENT')),
    status                    varchar(20)   NOT NULL CHECK (status IN ('REQUESTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    indication                varchar(1000),
    requested_by              uuid          NOT NULL,
    requested_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    cancel_reason             text,
    version                   bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_clinical_orders_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_orders_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES encounters (organization_id, id),
    CONSTRAINT fk_orders_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_orders_item FOREIGN KEY (organization_id, catalog_item_id)
        REFERENCES order_catalog_items (organization_id, id),
    CONSTRAINT fk_orders_requested_by FOREIGN KEY (organization_id, requested_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_orders_cancel CHECK (
        (status = 'CANCELLED') = (cancel_reason IS NOT NULL)
        AND (cancel_reason IS NULL OR length(trim(cancel_reason)) > 0))
);
CREATE INDEX ix_orders_encounter ON clinical_orders (encounter_id, requested_at);
CREATE INDEX ix_orders_worklist ON clinical_orders (facility_id, performing_department_id, status, requested_at);

CREATE TABLE order_events (
    id               uuid         PRIMARY KEY,
    organization_id  uuid         NOT NULL,
    order_id         uuid         NOT NULL,
    event_type       varchar(20)  NOT NULL CHECK (event_type IN ('REQUESTED', 'STARTED', 'COMPLETED', 'CANCELLED')),
    from_status      varchar(20),
    to_status        varchar(20)  NOT NULL,
    reason           text,
    actor_id         uuid         NOT NULL,
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_events_order FOREIGN KEY (organization_id, order_id)
        REFERENCES clinical_orders (organization_id, id),
    CONSTRAINT fk_order_events_actor FOREIGN KEY (organization_id, actor_id)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_order_events_reason CHECK (
        event_type <> 'CANCELLED' OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);
CREATE INDEX ix_order_events_order ON order_events (order_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE. Orders: only lifecycle columns can change.
-- ---------------------------------------------------------------------------
ALTER TABLE order_catalog_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_catalog_items FORCE ROW LEVEL SECURITY;
CREATE POLICY order_catalog_items_isolation ON order_catalog_items
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE clinical_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY clinical_orders_isolation ON clinical_orders
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE order_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_events FORCE ROW LEVEL SECURITY;
CREATE POLICY order_events_isolation ON order_events
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON order_catalog_items TO gcare_app;
GRANT UPDATE (name, performing_department_id, status, updated_at, version) ON order_catalog_items TO gcare_app;
GRANT SELECT, INSERT ON clinical_orders TO gcare_app;
GRANT UPDATE (status, cancel_reason, updated_at, version) ON clinical_orders TO gcare_app;
GRANT SELECT, INSERT ON order_events TO gcare_app;
