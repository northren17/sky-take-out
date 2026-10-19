-- 升级验证测试用的种子数据
-- admin 的密码是 123456 的 MD5（与 EmployeeServiceImpl 中 DigestUtils.md5DigestAsHex 的算法一致）
INSERT INTO employee (name, username, password, phone, sex, id_number, status, create_time, update_time, create_user, update_user)
VALUES ('管理员', 'admin', 'e10adc3949ba59abbe56e057f20f883e', '13800000000', '1', '110101199001010001', 1,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 1);
