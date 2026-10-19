package com.sky.service;

import com.sky.vo.CaptchaVO;

public interface CaptchaService {

    /**
     * 生成图形验证码并缓存到 Redis
     *
     * @return 验证码标识 + Base64 图片 + 有效期
     */
    CaptchaVO generate();

    /**
     * 校验验证码，校验通过后立即删除（一次性使用，防重放）
     *
     * @param captchaKey 验证码标识
     * @param code       用户填写的验证码
     * @return true 校验通过
     */
    boolean verify(String captchaKey, String code);
}
