package com.sky;

import com.sky.entity.ShoppingCart;
import com.sky.entity.UserCoupon;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.service.CouponDiscountStrategyFactory;
import com.sky.service.CouponOrderPricingService;
import com.sky.service.strategy.FullReductionCouponDiscountStrategy;
import com.sky.service.strategy.NoThresholdCouponDiscountStrategy;
import com.sky.service.strategy.RateDiscountCouponStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CouponOrderPricingServiceTest {
    private CouponOrderPricingService pricingService;

    @BeforeEach
    void setUp() {
        pricingService = new CouponOrderPricingService(mock(DishMapper.class), mock(SetmealMapper.class),
                new CouponDiscountStrategyFactory(List.of(
                        new FullReductionCouponDiscountStrategy(),
                        new RateDiscountCouponStrategy(),
                        new NoThresholdCouponDiscountStrategy())));
    }

    @Test
    void fixedDiscountCannotExceedEligibleSubtotal() {
        UserCoupon coupon = coupon(1, "15.00", "50.00", "0.0000", "0.00", 1, null);

        BigDecimal discount = pricingService.discount(coupon, List.of(cart("10.00", 2, 1L)));

        assertEquals(new BigDecimal("20.00"), discount);
    }

    @Test
    void percentageDiscountRoundsToTwoDecimalPlaces() {
        UserCoupon coupon = coupon(2, "0.00", "0.00", "0.9000", "0.00", 1, null);

        BigDecimal discount = pricingService.discount(coupon, List.of(cart("19.99", 1, 1L)));

        assertEquals(new BigDecimal("2.00"), discount);
    }

    @Test
    void restrictedCouponUsesOnlyEligibleItemsForItsThresholdAndDiscount() {
        UserCoupon coupon = coupon(1, "15.00", "5.00", "0.0000", "0.00", 2, "[8]");

        BigDecimal discount = pricingService.discount(coupon,
                List.of(cart("10.00", 2, 8L), cart("50.00", 1, 9L)));

        assertEquals(new BigDecimal("5.00"), discount);
    }

    @Test
    void restrictedCouponRejectsOrderBelowEligibleThreshold() {
        UserCoupon coupon = coupon(1, "15.00", "5.00", "0.0000", "0.00", 2, "[8]");

        assertThrows(CouponBusinessException.class,
                () -> pricingService.discount(coupon, List.of(cart("9.99", 1, 8L))));
    }

    @Test
    void percentageDiscountHonorsConfiguredMaximum() {
        UserCoupon coupon = coupon(2, "0.00", "0.00", "0.5000", "6.00", 1, null);

        BigDecimal discount = pricingService.discount(coupon, List.of(cart("20.00", 1, 1L)));

        assertEquals(new BigDecimal("6.00"), discount);
    }

    @Test
    void noThresholdCouponDoesNotApplyTheConfiguredMinimum() {
        UserCoupon coupon = coupon(3, "100.00", "5.00", "0.0000", "0.00", 1, null);

        BigDecimal discount = pricingService.discount(coupon, List.of(cart("4.00", 1, 1L)));

        assertEquals(new BigDecimal("4.00"), discount);
    }

    private UserCoupon coupon(int type, String threshold, String amount, String rate,
                              String maxDiscount, int scopeType, String scopeData) {
        UserCoupon coupon = new UserCoupon();
        coupon.setCouponTypeSnapshot(type);
        coupon.setThresholdAmountSnapshot(new BigDecimal(threshold));
        coupon.setDiscountAmountSnapshot(new BigDecimal(amount));
        coupon.setDiscountRateSnapshot(new BigDecimal(rate));
        coupon.setMaxDiscountAmountSnapshot(new BigDecimal(maxDiscount));
        coupon.setScopeTypeSnapshot(scopeType);
        coupon.setScopeDataSnapshot(scopeData);
        return coupon;
    }

    private ShoppingCart cart(String amount, int count, Long dishId) {
        ShoppingCart cart = new ShoppingCart();
        cart.setAmount(new BigDecimal(amount));
        cart.setNumber(count);
        cart.setDishId(dishId);
        return cart;
    }
}
