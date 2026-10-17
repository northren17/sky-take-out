package com.sky.exception;

/**
 * 用户注册失败
 */
public class RegisterFailedException extends BaseException {

    public RegisterFailedException(String msg) {
        super(msg);
    }
}
