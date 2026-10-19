-- Add the index used by bounded scans of unfinished coupon claim records.
-- Safe to rerun after migration-coupon-phase1.sql and migration-coupon-phase2.sql.

SET @coupon_claim_pending_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_claim_record'
      AND index_name = 'idx_coupon_claim_status_id'
);

SET @coupon_claim_pending_index_sql = IF(
    @coupon_claim_pending_index_exists = 0,
    'ALTER TABLE `coupon_claim_record` ADD KEY `idx_coupon_claim_status_id` (`status`, `id`)',
    'SELECT 1'
);

PREPARE coupon_claim_pending_index_stmt FROM @coupon_claim_pending_index_sql;
EXECUTE coupon_claim_pending_index_stmt;
DEALLOCATE PREPARE coupon_claim_pending_index_stmt;
