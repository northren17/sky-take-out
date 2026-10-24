-- Phase 1: pending order points, points account/ledger, and refund-event support.
-- Apply manually after checking the target database and taking a backup.
-- MySQL 5.7+ syntax; currency uses the existing DECIMAL(10,2) yuan convention.

CREATE TABLE IF NOT EXISTS `points_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `balance_points` BIGINT NOT NULL DEFAULT 0 COMMENT 'May be negative after a refund reversal',
    `total_earned` BIGINT NOT NULL DEFAULT 0 COMMENT 'Lifetime gross earned points',
    `total_spent` BIGINT NOT NULL DEFAULT 0 COMMENT 'Lifetime points spent on redemption',
    `version` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_account_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User points account';

CREATE TABLE IF NOT EXISTS `points_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `version` INT NOT NULL,
    `spend_step_amount` DECIMAL(10,2) NOT NULL COMMENT 'Eligible merchandise payment threshold in yuan',
    `points_per_step` INT NOT NULL,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=draft, 1=active, 2=archived',
    `effective_from` DATETIME NOT NULL,
    `effective_to` DATETIME DEFAULT NULL,
    `description` VARCHAR(255) DEFAULT NULL,
    `create_user` BIGINT DEFAULT NULL,
    `update_user` BIGINT DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_rule_version` (`version`),
    KEY `idx_points_rule_status_effective` (`status`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Versioned points earning rules';

INSERT INTO `points_rule`
    (`id`, `version`, `spend_step_amount`, `points_per_step`, `status`, `effective_from`, `effective_to`,
     `description`, `create_user`, `update_user`)
VALUES
    (1, 1, 10.00, 1, 1, CURRENT_TIMESTAMP, NULL,
     '每消费10元商品实付金额获得1积分，向下取整', NULL, NULL)
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `points_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `business_type` VARCHAR(32) NOT NULL COMMENT 'ORDER_EARN or REFUND_REVERSAL',
    `business_id` VARCHAR(64) NOT NULL COMMENT 'Order ID or refund number',
    `idempotency_key` VARCHAR(128) NOT NULL,
    `change_points` BIGINT NOT NULL COMMENT 'Can be zero for an order below the earning threshold',
    `balance_before` BIGINT NOT NULL,
    `balance_after` BIGINT NOT NULL,
    `rule_id` BIGINT DEFAULT NULL,
    `eligible_amount` DECIMAL(10,2) DEFAULT NULL COMMENT 'Net eligible merchandise amount in yuan',
    `reason` VARCHAR(255) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_ledger_idempotency` (`idempotency_key`),
    UNIQUE KEY `uk_points_ledger_business_event` (`business_type`, `business_id`),
    KEY `idx_points_ledger_user_time` (`user_id`, `create_time`, `id`),
    KEY `idx_points_ledger_rule` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only points ledger';

CREATE TABLE IF NOT EXISTS `points_pending_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `rule_id` BIGINT NOT NULL,
    `eligible_amount` DECIMAL(10,2) NOT NULL COMMENT 'Net paid merchandise amount; excludes packaging and delivery',
    `refunded_eligible_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    `spend_step_amount` DECIMAL(10,2) NOT NULL,
    `points_per_step` INT NOT NULL,
    `earned_points` BIGINT NOT NULL COMMENT 'Original points before refunds',
    `pending_points` BIGINT NOT NULL COMMENT 'Not spendable until administrator release',
    `issued_points` BIGINT NOT NULL DEFAULT 0,
    `reversed_points` BIGINT NOT NULL DEFAULT 0,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending approval, 1=released, 2=cancelled before release',
    `version` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_pending_order` (`order_id`),
    KEY `idx_points_pending_user_status` (`user_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Order points awaiting manual release';

-- Carry forward only paid orders still in fulfillment at deployment (statuses 2-4).
-- Historical completed orders are intentionally excluded; no retroactive rewards.
INSERT INTO `points_pending_order`
    (`order_id`, `user_id`, `rule_id`, `eligible_amount`, `refunded_eligible_amount`,
     `spend_step_amount`, `points_per_step`, `earned_points`, `pending_points`,
     `issued_points`, `reversed_points`, `status`, `version`)
SELECT o.`id`, o.`user_id`, r.`id`,
       s.`items_amount` - s.`coupon_discount`, 0.00,
       r.`spend_step_amount`, r.`points_per_step`,
       FLOOR((s.`items_amount` - s.`coupon_discount`) / r.`spend_step_amount`) * r.`points_per_step`,
       FLOOR((s.`items_amount` - s.`coupon_discount`) / r.`spend_step_amount`) * r.`points_per_step`,
       0, 0, 0, 0
FROM `orders` o
JOIN `order_coupon_snapshot` s ON s.`order_id` = o.`id`
JOIN `points_rule` r ON r.`status` = 1
    AND r.`effective_from` <= CURRENT_TIMESTAMP
    AND (r.`effective_to` IS NULL OR r.`effective_to` > CURRENT_TIMESTAMP)
WHERE o.`pay_status` = 1
  AND o.`status` IN (2, 3, 4)
  AND r.`spend_step_amount` > 0
  AND r.`points_per_step` > 0
  AND s.`items_amount` - s.`coupon_discount` >= 0
  AND (
      SELECT COUNT(*) FROM `points_rule` active_rule
      WHERE active_rule.`status` = 1
        AND active_rule.`effective_from` <= CURRENT_TIMESTAMP
        AND (active_rule.`effective_to` IS NULL OR active_rule.`effective_to` > CURRENT_TIMESTAMP)
  ) = 1
  AND NOT EXISTS (
      SELECT 1 FROM `points_pending_order` existing
      WHERE existing.`order_id` = o.`id`
  )
ON DUPLICATE KEY UPDATE `order_id` = `order_id`;

-- An order may have multiple refund events. Add event idempotency and merchandise
-- refund attribution, then remove the previous one-refund-per-order constraint.
SET @points_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `order_refund_record` ADD COLUMN `request_id` VARCHAR(64) DEFAULT NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_refund_record'
      AND COLUMN_NAME = 'request_id'
);
PREPARE points_stmt FROM @points_ddl;
EXECUTE points_stmt;
DEALLOCATE PREPARE points_stmt;

SET @points_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `order_refund_record` ADD COLUMN `eligible_goods_refund_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_refund_record'
      AND COLUMN_NAME = 'eligible_goods_refund_amount'
);
PREPARE points_stmt FROM @points_ddl;
EXECUTE points_stmt;
DEALLOCATE PREPARE points_stmt;

SET @points_ddl = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE `order_refund_record` DROP INDEX `uk_order_refund_order_id`',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_refund_record'
      AND INDEX_NAME = 'uk_order_refund_order_id'
);
PREPARE points_stmt FROM @points_ddl;
EXECUTE points_stmt;
DEALLOCATE PREPARE points_stmt;

SET @points_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `order_refund_record` ADD UNIQUE KEY `uk_order_refund_request_id` (`request_id`)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_refund_record'
      AND INDEX_NAME = 'uk_order_refund_request_id'
);
PREPARE points_stmt FROM @points_ddl;
EXECUTE points_stmt;
DEALLOCATE PREPARE points_stmt;

SET @points_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `order_refund_record` ADD KEY `idx_order_refund_order_status` (`order_id`, `status`)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_refund_record'
      AND INDEX_NAME = 'idx_order_refund_order_status'
);
PREPARE points_stmt FROM @points_ddl;
EXECUTE points_stmt;
DEALLOCATE PREPARE points_stmt;
