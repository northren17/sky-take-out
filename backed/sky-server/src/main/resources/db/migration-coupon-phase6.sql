-- Coupon exchange code and redemption ledger.
-- Run after migration-coupon-phase1.sql through migration-coupon-phase4.sql.

CREATE TABLE IF NOT EXISTS `coupon_exchange_code` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `activity_id` BIGINT NOT NULL,
    `expires_at` DATETIME DEFAULT NULL,
    `max_redeem_count` INT NOT NULL DEFAULT 1,
    `redeemed_count` INT NOT NULL DEFAULT 0,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=active, 1=disabled, 2=exhausted',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coupon_exchange_code` (`code`),
    KEY `idx_coupon_exchange_activity_status` (`activity_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Coupon exchange codes';

CREATE TABLE IF NOT EXISTS `coupon_exchange_redemption` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `exchange_code_id` BIGINT NOT NULL,
    `business_no` VARCHAR(64) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=processing, 1=redeemed, 2=failed',
    `failure_reason` VARCHAR(255) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coupon_exchange_redemption_business_no` (`business_no`),
    UNIQUE KEY `uk_coupon_exchange_redemption_user` (`exchange_code_id`, `user_id`),
    KEY `idx_coupon_exchange_redemption_status_time` (`status`, `create_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Coupon exchange idempotency and processing records';
