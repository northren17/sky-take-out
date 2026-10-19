package com.sky.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 图形验证码
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "图形验证码")
public class CaptchaVO implements Serializable {

    @Schema(description = "验证码标识，登录/注册时需要原样回传")
    private String captchaKey;

    @Schema(description = "验证码图片，Base64（可直接拼成 data:image/png;base64,xxx 展示）")
    private String image;

    @Schema(description = "验证码有效期（秒）")
    private Long expireIn;
}
