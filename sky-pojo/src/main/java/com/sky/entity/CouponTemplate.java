package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CouponTemplate {
    private Long id;
    private String name;
    private Integer couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal maxDiscountAmount;
    private Integer validityType;
    private Integer validityDays;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
    private Integer scopeType;
    private String scopeData;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long createUser;
    private Long updateUser;
}
