-- Digital menu (QR menu) published by a till: one per company, readable by anyone with the link.
CREATE TABLE menus (
    org_id BIGINT PRIMARY KEY REFERENCES orgs(id),
    slug VARCHAR(40) NOT NULL UNIQUE,
    data TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);
