-- The staff's message to the customer with the answer to a web order: the reason of a refusal,
-- or a note with the accepted order. Shown on the customer's order page and in the back office.
ALTER TABLE web_orders ADD COLUMN reply TEXT;
