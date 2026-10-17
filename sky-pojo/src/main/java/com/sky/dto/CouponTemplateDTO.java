package com.sky.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CouponTemplateDTO {
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
    private List<Long> scopeIds;
    private Integer status;
}
