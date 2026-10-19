package com.sky.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserCouponVO {
    private Long id;
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
    private Integer status;
    private LocalDateTime acquiredTime;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
    private Long lockedOrderId;
    private Long usedOrderId;
    private LocalDateTime usedTime;
}
