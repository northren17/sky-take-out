package com.sky.service;

import com.sky.entity.UserCoupon;

import java.math.BigDecimal;

public interface CouponDiscountStrategy {
    int couponType();
    BigDecimal calculate(UserCoupon coupon, BigDecimal eligibleAmount);
}
