-- What is going on at each till right now (open tables, cash drawer, customers...), shown read-only in the back office
CREATE TABLE till_snapshots (
    device_id BIGINT NOT NULL PRIMARY KEY REFERENCES devices(id),
    org_id BIGINT NOT NULL REFERENCES orgs(id),
    data TEXT NOT NULL,
    taken_at BIGINT NOT NULL,
    received_at BIGINT NOT NULL
);
CREATE INDEX till_snapshots_org ON till_snapshots(org_id)
