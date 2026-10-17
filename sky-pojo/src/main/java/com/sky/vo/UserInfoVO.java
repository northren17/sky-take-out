package com.sky.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 当前登录用户信息（不含密码）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户信息")
public class UserInfoVO implements Serializable {

    @Schema(description = "用户 id")
    private Long id;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "性别 0 女 1 男")
    private String sex;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "注册时间")
    private LocalDateTime createTime;
}
