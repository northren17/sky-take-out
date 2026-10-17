package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderCouponSnapshot {
    private Long orderId;
    private Long userCouponId;
    private Long activityId;
    private Long templateId;
    private String couponNameSnapshot;
    private Integer couponTypeSnapshot;
    private BigDecimal thresholdAmountSnapshot;
    private BigDecimal discountAmountSnapshot;
    private BigDecimal discountRateSnapshot;
    private BigDecimal maxDiscountAmountSnapshot;
    private Integer scopeTypeSnapshot;
    private String scopeDataSnapshot;
    private BigDecimal itemsAmount;
    private BigDecimal packFee;
    private BigDecimal deliveryFee;
    private BigDecimal couponDiscount;
    private BigDecimal payableAmount;
    private Integer couponState;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
