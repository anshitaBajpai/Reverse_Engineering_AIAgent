-- Move the per-user query/document caps from lifetime to per-day.
--
-- usage_period_date records which UTC day the queries_used / documents_used
-- counters belong to. A request on a later day resets BOTH counters (handled in
-- UserQuotaService) before the limit is applied. NULL means "no period yet".
ALTER TABLE users ADD COLUMN usage_period_date DATE;

-- Existing rows carry lifetime totals from V3 that must not count against the
-- first day under the new scheme.
UPDATE users SET queries_used = 0, documents_used = 0;
