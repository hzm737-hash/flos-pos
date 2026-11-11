-- A till deleted in the back office stays in the database for its receipts but is no longer shown or allowed in
ALTER TABLE devices ADD COLUMN deleted_at BIGINT
