package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CouponOrderPricingService {
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("6.00");
    private static final BigDecimal PACK_FEE_PER_ITEM = new BigDecimal("1.00");

    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;
    private final CouponDiscountStrategyFactory discountStrategyFactory;

    public BigDecimal itemsAmount(List<ShoppingCart> cart) {
        return cart.stream()
                .map(line -> line.getAmount().multiply(BigDecimal.valueOf(line.getNumber())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal packFee(List<ShoppingCart> cart) {
        int itemCount = cart.stream().mapToInt(ShoppingCart::getNumber).sum();
        return PACK_FEE_PER_ITEM.multiply(BigDecimal.valueOf(itemCount))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal deliveryFee() {
        return DELIVERY_FEE;
    }

    public BigDecimal discount(UserCoupon coupon, List<ShoppingCart> cart) {
        BigDecimal eligibleAmount = eligibleAmount(coupon, cart);
        if (eligibleAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CouponBusinessException("所选优惠券不适用于当前商品");
        }
        return discountStrategyFactory.getStrategy(coupon.getCouponTypeSnapshot())
                .calculate(coupon, eligibleAmount);
    }

    private BigDecimal eligibleAmount(UserCoupon coupon, List<ShoppingCart> cart) {
        if (coupon.getScopeTypeSnapshot() == null || coupon.getScopeTypeSnapshot() == 1) {
            return itemsAmount(cart);
        }
        String scopeData = coupon.getScopeDataSnapshot();
        if (scopeData == null || scopeData.isBlank()) {
            throw new CouponBusinessException("优惠券适用范围配置无效");
        }
        Set<Long> allowedIds = JSON.parseArray(scopeData, Long.class).stream().collect(Collectors.toSet());
        BigDecimal eligible = BigDecimal.ZERO;
        for (ShoppingCart line : cart) {
            boolean applies = false;
            if (coupon.getScopeTypeSnapshot() == 2) {
                applies = line.getDishId() != null && allowedIds.contains(line.getDishId());
            } else if (coupon.getScopeTypeSnapshot() == 3) {
                Long categoryId = null;
                if (line.getDishId() != null) {
                    Dish dish = dishMapper.getByid(line.getDishId());
                    if (dish != null) categoryId = dish.getCategoryId();
                } else if (line.getSetmealId() != null) {
                    Setmeal setmeal = setmealMapper.getById(line.getSetmealId());
                    if (setmeal != null) categoryId = setmeal.getCategoryId();
                }
                applies = categoryId != null && allowedIds.contains(categoryId);
            } else {
                throw new CouponBusinessException("优惠券适用范围类型无效");
            }
            if (applies) {
                eligible = eligible.add(line.getAmount().multiply(BigDecimal.valueOf(line.getNumber())));
            }
        }
        return eligible.setScale(2, RoundingMode.HALF_UP);
    }
}
