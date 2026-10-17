-- Durable RabbitMQ outbox retry metadata for coupon_claim_record.
-- Run after migration-coupon-phase1.sql through migration-coupon-phase3.sql.

SET @coupon_claim_dispatch_attempts_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_claim_record'
      AND column_name = 'dispatch_attempts'
);
SET @coupon_claim_dispatch_attempts_sql = IF(
    @coupon_claim_dispatch_attempts_exists = 0,
    'ALTER TABLE `coupon_claim_record` ADD COLUMN `dispatch_attempts` INT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE coupon_claim_dispatch_attempts_stmt FROM @coupon_claim_dispatch_attempts_sql;
EXECUTE coupon_claim_dispatch_attempts_stmt;
DEALLOCATE PREPARE coupon_claim_dispatch_attempts_stmt;

SET @coupon_claim_next_dispatch_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_claim_record'
      AND column_name = 'next_dispatch_time'
);
SET @coupon_claim_next_dispatch_sql = IF(
    @coupon_claim_next_dispatch_exists = 0,
    'ALTER TABLE `coupon_claim_record` ADD COLUMN `next_dispatch_time` DATETIME NULL',
    'SELECT 1'
);
PREPARE coupon_claim_next_dispatch_stmt FROM @coupon_claim_next_dispatch_sql;
EXECUTE coupon_claim_next_dispatch_stmt;
DEALLOCATE PREPARE coupon_claim_next_dispatch_stmt;

SET @coupon_claim_dispatch_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_claim_record'
      AND index_name = 'idx_coupon_claim_dispatch'
);
SET @coupon_claim_dispatch_index_sql = IF(
    @coupon_claim_dispatch_index_exists = 0,
    'ALTER TABLE `coupon_claim_record` ADD KEY `idx_coupon_claim_dispatch` (`status`, `next_dispatch_time`, `id`)',
    'SELECT 1'
);
PREPARE coupon_claim_dispatch_index_stmt FROM @coupon_claim_dispatch_index_sql;
EXECUTE coupon_claim_dispatch_index_stmt;
DEALLOCATE PREPARE coupon_claim_dispatch_index_stmt;
