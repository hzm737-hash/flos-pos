-- One login can manage several companies (each with its own IČO/DIČ). A session works in
-- one company at a time, and the role comes from the membership in that company.
CREATE TABLE memberships (
    user_id BIGINT NOT NULL REFERENCES users(id),
    org_id BIGINT NOT NULL REFERENCES orgs(id),
    role VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, org_id)
);
CREATE INDEX memberships_org ON memberships(org_id);
INSERT INTO memberships (user_id, org_id, role) SELECT id, org_id, role FROM users;
