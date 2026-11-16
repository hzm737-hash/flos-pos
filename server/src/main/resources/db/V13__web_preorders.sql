-- A pre-order for a later day waits for the till until the end of that day (not only 6 hours),
-- so an order sent while the restaurant is closed is still there when it opens.
ALTER TABLE web_orders ADD COLUMN expires_at BIGINT;
