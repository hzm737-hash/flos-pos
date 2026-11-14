-- When the company takes orders from the tables (QR) and from the website: ordering hours and the
-- "stop orders" switch, set on a till. Orders outside them are turned away here.
CREATE TABLE order_control (
    org_id BIGINT NOT NULL PRIMARY KEY REFERENCES orgs(id),
    data TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Tables with an open bill on each till, so those guests may still order after hours.
CREATE TABLE open_tables (
    device_id BIGINT NOT NULL PRIMARY KEY REFERENCES devices(id),
    org_id BIGINT NOT NULL REFERENCES orgs(id),
    tables TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX open_tables_org ON open_tables(org_id);
