CREATE TABLE IF NOT EXISTS `user_dining_preference` (
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `taste_preferences` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '口味偏好，以逗号分隔',
    `daily_budget` DECIMAL(10,2) NOT NULL COMMENT '每日餐费限额，单位元',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户口味偏好与每日餐费限额';
