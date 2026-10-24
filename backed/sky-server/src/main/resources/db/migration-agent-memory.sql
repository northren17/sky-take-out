-- Run against the configured MySQL database before deploying the Java service.
-- CREATE TABLE IF NOT EXISTS makes repeated execution safe.

CREATE TABLE IF NOT EXISTS `user_agent_memory_setting` (
    `user_id` BIGINT NOT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0=disabled, 1=enabled',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user voice-agent memory consent';

CREATE TABLE IF NOT EXISTS `user_agent_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `category` VARCHAR(24) NOT NULL COMMENT 'profile or ordering',
    `memory_key` VARCHAR(48) NOT NULL,
    `memory_value` VARCHAR(160) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_memory_user_category_key` (`user_id`, `category`, `memory_key`),
    KEY `idx_agent_memory_user_update` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Explicitly saved voice-agent preferences';
