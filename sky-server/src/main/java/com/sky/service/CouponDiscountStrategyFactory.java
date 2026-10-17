package com.sky.service;

import com.sky.exception.CouponBusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CouponDiscountStrategyFactory {
    private final Map<Integer, CouponDiscountStrategy> strategies;

    public CouponDiscountStrategyFactory(List<CouponDiscountStrategy> strategies) {
        this.strategies = strategies.stream().collect(Collectors.toUnmodifiableMap(
                CouponDiscountStrategy::couponType, Function.identity()));
    }

    public CouponDiscountStrategy getStrategy(Integer couponType) {
        if (couponType == null) throw new CouponBusinessException("优惠券类型无效");
        CouponDiscountStrategy strategy = strategies.get(couponType);
        if (strategy == null) throw new CouponBusinessException("优惠券类型无效");
        return strategy;
    }
}
