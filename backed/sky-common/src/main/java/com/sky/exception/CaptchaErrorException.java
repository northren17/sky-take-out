package com.sky.exception;

/**
 * 验证码错误或已过期
 */
public class CaptchaErrorException extends BaseException {

    public CaptchaErrorException(String msg) {
        super(msg);
    }
}
