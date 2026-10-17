package com.sky.service.strategy;

import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.service.CouponDiscountStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class NoThresholdCouponDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public int couponType() {
        return 3;
    }

    @Override
    public BigDecimal calculate(UserCoupon coupon, BigDecimal eligibleAmount) {
        BigDecimal amount = coupon.getDiscountAmountSnapshot();
        if (amount == null || amount.signum() <= 0) {
            throw new CouponBusinessException("无门槛优惠券金额配置无效");
        }
        return amount.min(eligibleAmount).setScale(2, RoundingMode.HALF_UP);
    }
}
