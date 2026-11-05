-- Item quantities can be fractional, such as a half portion (0.5), kept to three decimal places.
ALTER TABLE sale_items ALTER COLUMN quantity SET DATA TYPE NUMERIC(12,3);
