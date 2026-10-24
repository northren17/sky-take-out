-- Order pricing and coupon lifecycle snapshots (MySQL 8+).
-- Run after migration-coupon-phase1.sql. This adds dedicated tables only and does not alter orders.
-- Safe to rerun: CREATE TABLE IF NOT EXISTS preserves existing rows and definitions.

CREATE TABLE IF NOT EXISTS `order_coupon_snapshot` (
    `order_id` BIGINT NOT NULL,
    `user_coupon_id` BIGINT DEFAULT NULL,
    `activity_id` BIGINT DEFAULT NULL,
    `template_id` BIGINT DEFAULT NULL,
    `coupon_name_snapshot` VARCHAR(100) DEFAULT NULL,
    `coupon_type_snapshot` TINYINT DEFAULT NULL,
    `threshold_amount_snapshot` DECIMAL(10,2) DEFAULT NULL COMMENT 'yuan',
    `discount_amount_snapshot` DECIMAL(10,2) DEFAULT NULL COMMENT 'yuan',
    `discount_rate_snapshot` DECIMAL(6,4) DEFAULT NULL,
    `max_discount_amount_snapshot` DECIMAL(10,2) DEFAULT NULL COMMENT 'yuan; 0 means no cap',
    `scope_type_snapshot` TINYINT DEFAULT NULL,
    `scope_data_snapshot` JSON DEFAULT NULL,
    `items_amount` DECIMAL(10,2) NOT NULL COMMENT 'Dish/set-meal subtotal in yuan',
    `pack_fee` DECIMAL(10,2) NOT NULL COMMENT 'Packaging fee in yuan',
    `delivery_fee` DECIMAL(10,2) NOT NULL COMMENT 'Delivery fee in yuan',
    `coupon_discount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'Coupon discount in yuan',
    `payable_amount` DECIMAL(10,2) NOT NULL COMMENT 'Final amount in yuan',
    `coupon_state` TINYINT NOT NULL DEFAULT 0 COMMENT '0=no coupon, 1=locked, 2=used, 3=released, 4=restored, 5=expired after refund',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    KEY `idx_order_coupon_snapshot_user_coupon` (`user_coupon_id`),
    KEY `idx_order_coupon_snapshot_activity` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable order pricing and coupon snapshot';

CREATE TABLE IF NOT EXISTS `order_refund_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `out_refund_no` VARCHAR(64) NOT NULL,
    `order_id` BIGINT NOT NULL,
    `order_number` VARCHAR(64) NOT NULL,
    `refund_amount` DECIMAL(10,2) NOT NULL COMMENT 'yuan',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending, 1=success, 2=failed',
    `response_body` TEXT DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_refund_out_refund_no` (`out_refund_no`),
    UNIQUE KEY `uk_order_refund_order_id` (`order_id`),
    KEY `idx_order_refund_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Idempotent order refund event tracking';
