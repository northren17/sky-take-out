-- 升级验证测试用的最小表结构（H2，MySQL 兼容模式）
-- 字段与实体、Mapper 中的 SQL 保持一致
-- 注意：user 在 H2 中是保留字，必须用反引号包裹（MySQL 下同样写法）

-- ==================== 管理端：员工表 ====================
DROP TABLE IF EXISTS employee;

CREATE TABLE employee
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(32)  NOT NULL,
    username    VARCHAR(32)  NOT NULL,
    password    VARCHAR(64)  NOT NULL,
    phone       VARCHAR(11),
    sex         VARCHAR(2),
    id_number   VARCHAR(18),
    status      INT DEFAULT 1,
    create_time DATETIME,
    update_time DATETIME,
    create_user BIGINT,
    update_user BIGINT
);

-- ==================== 用户端：用户表 ====================
-- 对应 db/migration-user-auth.sql：password（BCrypt 密文）与 username（登录账号，唯一）
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user`
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    openid      VARCHAR(45),
    password    VARCHAR(100),
    username    VARCHAR(45),
    name        VARCHAR(32),
    phone       VARCHAR(11),
    sex         VARCHAR(2),
    id_number   VARCHAR(18),
    avatar      VARCHAR(500),
    create_time DATETIME,
    CONSTRAINT uk_username UNIQUE (username)
);

DROP TABLE IF EXISTS user_agent_memory;
DROP TABLE IF EXISTS user_agent_memory_setting;

CREATE TABLE user_agent_memory_setting
(
    user_id     BIGINT PRIMARY KEY,
    enabled     TINYINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_agent_memory
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    category     VARCHAR(24) NOT NULL,
    memory_key   VARCHAR(48) NOT NULL,
    memory_value VARCHAR(160) NOT NULL,
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_memory_user_category_key UNIQUE (user_id, category, memory_key)
);

DROP TABLE IF EXISTS points_ledger;
DROP TABLE IF EXISTS points_pending_order;
DROP TABLE IF EXISTS points_rule;
DROP TABLE IF EXISTS points_account;

CREATE TABLE points_account
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    balance_points BIGINT NOT NULL DEFAULT 0,
    total_earned   BIGINT NOT NULL DEFAULT 0,
    total_spent    BIGINT NOT NULL DEFAULT 0,
    version        INT NOT NULL DEFAULT 0,
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_points_account_user UNIQUE (user_id)
);

CREATE TABLE points_rule
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    version           INT NOT NULL,
    spend_step_amount DECIMAL(10,2) NOT NULL,
    points_per_step   INT NOT NULL,
    status            TINYINT NOT NULL DEFAULT 0,
    effective_from    DATETIME NOT NULL,
    effective_to      DATETIME,
    description       VARCHAR(255),
    create_user       BIGINT,
    update_user       BIGINT,
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_points_rule_version UNIQUE (version)
);

INSERT INTO points_rule
    (version, spend_step_amount, points_per_step, status, effective_from, description)
VALUES
    (1, 10.00, 1, 1, CURRENT_TIMESTAMP, 'test default points rule');

CREATE TABLE points_pending_order
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id                 BIGINT NOT NULL,
    user_id                  BIGINT NOT NULL,
    rule_id                  BIGINT NOT NULL,
    eligible_amount          DECIMAL(10,2) NOT NULL,
    refunded_eligible_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    spend_step_amount        DECIMAL(10,2) NOT NULL,
    points_per_step          INT NOT NULL,
    earned_points            BIGINT NOT NULL,
    pending_points           BIGINT NOT NULL,
    issued_points            BIGINT NOT NULL DEFAULT 0,
    reversed_points          BIGINT NOT NULL DEFAULT 0,
    status                   TINYINT NOT NULL DEFAULT 0,
    version                  INT NOT NULL DEFAULT 0,
    create_time              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_points_pending_order UNIQUE (order_id)
);

CREATE TABLE points_ledger
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    business_type   VARCHAR(32) NOT NULL,
    business_id     VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    change_points   BIGINT NOT NULL,
    balance_before  BIGINT NOT NULL,
    balance_after   BIGINT NOT NULL,
    rule_id         BIGINT,
    eligible_amount DECIMAL(10,2),
    reason          VARCHAR(255) NOT NULL,
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_points_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_points_ledger_business_event UNIQUE (business_type, business_id)
);

CREATE INDEX idx_points_ledger_user_time ON points_ledger (user_id, create_time, id);
