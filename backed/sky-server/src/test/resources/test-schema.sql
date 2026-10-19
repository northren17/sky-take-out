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
