-- Allow incomplete draft activities to be saved before their schedule is configured.
-- Run after migration-coupon-phase1.sql through migration-coupon-phase6.sql.

SET @coupon_activity_start_nullable = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_activity'
      AND column_name = 'start_time'
      AND is_nullable = 'YES'
);
SET @coupon_activity_start_sql = IF(
    @coupon_activity_start_nullable = 0,
    'ALTER TABLE `coupon_activity` MODIFY COLUMN `start_time` DATETIME DEFAULT NULL',
    'SELECT 1'
);
PREPARE coupon_activity_start_stmt FROM @coupon_activity_start_sql;
EXECUTE coupon_activity_start_stmt;
DEALLOCATE PREPARE coupon_activity_start_stmt;

SET @coupon_activity_end_nullable = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_activity'
      AND column_name = 'end_time'
      AND is_nullable = 'YES'
);
SET @coupon_activity_end_sql = IF(
    @coupon_activity_end_nullable = 0,
    'ALTER TABLE `coupon_activity` MODIFY COLUMN `end_time` DATETIME DEFAULT NULL',
    'SELECT 1'
);
PREPARE coupon_activity_end_stmt FROM @coupon_activity_end_sql;
EXECUTE coupon_activity_end_stmt;
DEALLOCATE PREPARE coupon_activity_end_stmt;
