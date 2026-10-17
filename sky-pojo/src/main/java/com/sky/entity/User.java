package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 微信用户唯一标识
     * <p>
     * 说明：网页版注册/账号密码登录复用该字段存放登录账号（用户名），
     * 微信小程序登录时存放真实 openid，两者语义由 {@code username} 区分。
     */
    private String openid;

    /**
     * 登录账号（网页版注册时写入，与 openid 列取同一个值；微信登录用户为 null）
     */
    private String username;

    /**
     * 登录密码（BCrypt 加密后的密文，形如 $2a$10$...；微信登录用户为 null）
     */
    private String password;

    //姓名
    private String name;

    //手机号
    private String phone;

    //性别 0 女 1 男
    private String sex;

    //身份证号
    private String idNumber;

    //头像
    private String avatar;

    //注册时间
    private LocalDateTime createTime;
}
