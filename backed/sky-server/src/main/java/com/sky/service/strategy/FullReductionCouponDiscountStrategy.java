package com.sky.service.strategy;

import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.service.CouponDiscountStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class FullReductionCouponDiscountStrategy implements CouponDiscountStrategy {
    @Override
    public int couponType() {
        return 1;
    }

    @Override
    public BigDecimal calculate(UserCoupon coupon, BigDecimal eligibleAmount) {
        BigDecimal threshold = coupon.getThresholdAmountSnapshot();
        if (threshold == null || eligibleAmount.compareTo(threshold) < 0) {
            throw new CouponBusinessException("订单未达到优惠券使用门槛");
        }
        BigDecimal amount = coupon.getDiscountAmountSnapshot();
        if (amount == null || amount.signum() <= 0) {
            throw new CouponBusinessException("满减优惠券金额配置无效");
        }
        return amount.min(eligibleAmount).setScale(2, RoundingMode.HALF_UP);
    }
}
