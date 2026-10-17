package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO implements Serializable {

    private Long id;
    private String openid;
    private String token;

    /**
     * 登录账号（网页版账号密码登录返回；微信登录时为 null）
     */
    private String username;

    /**
     * 姓名/昵称（网页版注册时填写；微信登录时为 null）
     */
    private String name;
}
