package com.sky.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * C端用户注册
 */
@Data
@Schema(description = "用户注册时传递的数据模型")
public class UserRegisterDTO implements Serializable {

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "登录密码")
    private String password;

    @Schema(description = "确认密码")
    private String confirmPassword;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "验证码")
    private String code;

    @Schema(description = "验证码标识（由 /user/user/captcha 返回）")
    private String captchaKey;
}
