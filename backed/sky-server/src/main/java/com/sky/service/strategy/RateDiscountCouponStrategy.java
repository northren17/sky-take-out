package com.sky.service.strategy;

import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.service.CouponDiscountStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class RateDiscountCouponStrategy implements CouponDiscountStrategy {
    @Override
    public int couponType() {
        return 2;
    }

    @Override
    public BigDecimal calculate(UserCoupon coupon, BigDecimal eligibleAmount) {
        BigDecimal threshold = coupon.getThresholdAmountSnapshot();
        if (threshold == null || eligibleAmount.compareTo(threshold) < 0) {
            throw new CouponBusinessException("订单未达到优惠券使用门槛");
        }
        BigDecimal rate = coupon.getDiscountRateSnapshot();
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new CouponBusinessException("优惠券折扣比例配置无效");
        }
        BigDecimal discount = eligibleAmount.multiply(BigDecimal.ONE.subtract(rate));
        BigDecimal maximum = coupon.getMaxDiscountAmountSnapshot();
        if (maximum != null && maximum.signum() > 0) {
            discount = discount.min(maximum);
        }
        return discount.min(eligibleAmount).setScale(2, RoundingMode.HALF_UP);
    }
}
