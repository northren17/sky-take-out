package com.sky.constant;

/**
 * Redis key 常量
 */
public class RedisKeyConstant {

    /**
     * C端用户登录令牌：{@code user:token:{userId}} -> jwt 字符串
     * <p>
     * 用途：让无状态的 JWT 变成“可在服务端注销 / 强制下线”的会话。
     */
    public static final String USER_TOKEN = "user:token:";

    /**
     * 图形验证码：{@code captcha:{captchaKey}} -> 验证码答案
     */
    public static final String CAPTCHA = "captcha:";

    /**
     * 图形验证码有效期（秒）
     */
    public static final long CAPTCHA_TTL_SECONDS = 120L;

    /**
     * Coupon claim activity state, per-user counters, and in-flight request markers.
     */
    public static final String COUPON_ACTIVITY = "coupon:activity:";
    public static final String COUPON_ACTIVITY_USERS = "coupon:activity:users:";
    public static final String COUPON_ACTIVITY_REQUESTS = "coupon:activity:requests:";
    public static final String COUPON_ACTIVITY_RECONCILE_LOCK = "coupon:activity:reconcile-lock:";
    public static final String COUPON_RULE_CACHE_VERSION = "coupon:rule:cache:version";
    public static final String COUPON_RULE_CACHE_TEMPLATE = "coupon:rule:template:";
    public static final String COUPON_RULE_CACHE_ACTIVITY = "coupon:rule:activity:";
}
