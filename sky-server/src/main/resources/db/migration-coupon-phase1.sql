-- Coupon foundation schema (MySQL 8+).
-- Safe to rerun: CREATE TABLE IF NOT EXISTS leaves existing rows and definitions untouched.
-- Run against the same database configured by spring.datasource before deploying the new server.

CREATE TABLE IF NOT EXISTS `coupon_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `coupon_type` TINYINT NOT NULL COMMENT '1=fixed amount, 2=discount rate, 3=no threshold',
    `threshold_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'yuan',
    `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'yuan',
    `discount_rate` DECIMAL(6,4) NOT NULL DEFAULT 1.0000 COMMENT 'multiplier, e.g. 0.8500',
    `max_discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'yuan; 0 means no cap',
    `validity_type` TINYINT NOT NULL COMMENT '1=relative days, 2=fixed interval',
    `validity_days` INT DEFAULT NULL,
    `valid_start_time` DATETIME DEFAULT NULL,
    `valid_end_time` DATETIME DEFAULT NULL,
    `scope_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1=all, 2=dish ids, 3=category ids',
    `scope_data` JSON DEFAULT NULL COMMENT 'JSON array of dish/category ids',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=disabled, 1=enabled',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `create_user` BIGINT DEFAULT NULL,
    `update_user` BIGINT DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_coupon_template_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Coupon rules';

CREATE TABLE IF NOT EXISTS `coupon_activity` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `template_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `stock_total` INT NOT NULL,
    `stock_remaining` INT NOT NULL,
    `issued_count` INT NOT NULL DEFAULT 0,
    `used_count` INT NOT NULL DEFAULT 0,
    `per_user_limit` INT NOT NULL DEFAULT 1,
    `start_time` DATETIME NOT NULL,
    `end_time` DATETIME NOT NULL,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=draft, 1=active, 2=paused',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `create_user` BIGINT DEFAULT NULL,
    `update_user` BIGINT DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_coupon_activity_status_time` (`status`, `start_time`, `end_time`),
    KEY `idx_coupon_activity_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Coupon campaigns';

CREATE TABLE IF NOT EXISTS `coupon_claim_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `business_no` VARCHAR(64) NOT NULL COMMENT 'Client idempotency key; unique per claim attempt',
    `activity_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=processing, 1=succeeded, 2=failed',
    `user_coupon_id` BIGINT DEFAULT NULL,
    `failure_reason` VARCHAR(255) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coupon_claim_business_no` (`business_no`),
    KEY `idx_coupon_claim_user_activity` (`user_id`, `activity_id`, `status`),
    KEY `idx_coupon_claim_activity_status` (`activity_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Coupon claim idempotency and processing records';

CREATE TABLE IF NOT EXISTS `user_coupon` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `claim_record_id` BIGINT DEFAULT NULL,
    `activity_id` BIGINT DEFAULT NULL,
    `template_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `coupon_name_snapshot` VARCHAR(100) NOT NULL,
    `coupon_type_snapshot` TINYINT NOT NULL,
    `threshold_amount_snapshot` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'yuan',
    `discount_amount_snapshot` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'yuan',
    `discount_rate_snapshot` DECIMAL(6,4) NOT NULL DEFAULT 1.0000,
    `max_discount_amount_snapshot` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'yuan; 0 means no cap',
    `scope_type_snapshot` TINYINT NOT NULL DEFAULT 1,
    `scope_data_snapshot` JSON DEFAULT NULL,
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=unused, 1=locked, 2=used, 3=expired',
    `acquired_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `valid_start_time` DATETIME NOT NULL,
    `valid_end_time` DATETIME NOT NULL,
    `locked_order_id` BIGINT DEFAULT NULL,
    `used_order_id` BIGINT DEFAULT NULL,
    `used_time` DATETIME DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_coupon_claim_record` (`claim_record_id`),
    KEY `idx_user_coupon_user_status_validity` (`user_id`, `status`, `valid_end_time`),
    KEY `idx_user_coupon_activity_user` (`activity_id`, `user_id`),
    KEY `idx_user_coupon_locked_order` (`locked_order_id`),
    KEY `idx_user_coupon_used_order` (`used_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Issued coupons owned by users';
