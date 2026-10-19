-- ============================================================================
-- C端用户注册 / 账号密码登录 —— 数据库变更脚本
-- 适用库：sky_take_out   适用表：user
-- 说明：只新增列与索引，不修改、不删除任何既有数据，管理端（employee）表完全不受影响。
-- 重复执行会因列/索引已存在而报错，属正常现象（MySQL 不支持 ADD COLUMN IF NOT EXISTS）。
-- ============================================================================

-- 1) 新增 password 列：存放 BCrypt 密文（形如 $2a$10$...，固定 60 字符，留 100 足够）
ALTER TABLE `user`
    ADD COLUMN `password` varchar(100) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '登录密码（BCrypt）' AFTER `openid`;

-- 2) 新增 username 列：网页版登录账号（微信登录用户该列为 NULL）
ALTER TABLE `user`
    ADD COLUMN `username` varchar(45) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '登录账号' AFTER `password`;

-- 3) 账号唯一索引：保证并发注册时不会出现重复账号
--    注意 MySQL 唯一索引允许多个 NULL，因此不影响既有/未来的微信登录用户
ALTER TABLE `user`
    ADD UNIQUE KEY `uk_username` (`username`);

-- 4) 校验
-- DESCRIBE `user`;
-- SHOW INDEX FROM `user` WHERE Key_name = 'uk_username';
SHOW COLUMNS FROM `user` WHERE Field IN ('password', 'username');
SHOW INDEX FROM `user` WHERE Key_name = 'uk_username';