package com.sky.constant;

/**
 * 信息提示常量类
 */
public class MessageConstant {

    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String ACCOUNT_LOCKED = "账号被锁定";
    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String USER_NOT_LOGIN = "用户未登录";
    public static final String CATEGORY_BE_RELATED_BY_SETMEAL = "当前分类关联了套餐,不能删除";
    public static final String CATEGORY_BE_RELATED_BY_DISH = "当前分类关联了菜品,不能删除";
    public static final String SHOPPING_CART_IS_NULL = "购物车数据为空，不能下单";
    public static final String ADDRESS_BOOK_IS_NULL = "用户地址为空，不能下单";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String SETMEAL_ENABLE_FAILED = "套餐内包含未启售菜品，无法启售";
    public static final String PASSWORD_EDIT_FAILED = "密码修改失败";
    public static final String DISH_ON_SALE = "起售中的菜品不能删除";
    public static final String SETMEAL_ON_SALE = "起售中的套餐不能删除";
    public static final String DISH_BE_RELATED_BY_SETMEAL = "当前菜品关联了套餐,不能删除";
    public static final String ORDER_STATUS_ERROR = "订单状态错误";
    public static final String ORDER_NOT_FOUND = "订单不存在";
    public static final String ALREDAY_EXISTS = "已存在";
    public static final String FLAVOR_NULL = "菜品未关联口味信息，无法起售";
    public static final String DISH_NOT_FOUND = "菜品不存在";
    public static final String SETMEAL_NOT_FOUND = "套餐不存在";

    /* ==================== C端用户注册 / 登录 ==================== */
    public static final String USERNAME_ALREADY_EXISTS = "该账号已被注册";
    public static final String REGISTER_FAILED = "注册失败";
    public static final String USERNAME_IS_NULL = "账号不能为空";
    public static final String PASSWORD_IS_NULL = "密码不能为空";
    public static final String PASSWORD_NOT_MATCH = "两次输入的密码不一致";
    public static final String USERNAME_LENGTH_ERROR = "账号长度需在 4~32 位之间";
    public static final String PASSWORD_LENGTH_ERROR = "密码长度需在 6~32 位之间";
    public static final String CAPTCHA_IS_NULL = "验证码不能为空";
    public static final String CAPTCHA_ERROR = "验证码错误或已过期";
    public static final String CAPTCHA_GENERATE_FAILED = "验证码生成失败";
}