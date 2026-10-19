package com.sky.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AgentCouponSummaryVO {
    private String name;
    private Integer couponType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal maxDiscountAmount;
    private Integer scopeType;
    private Integer status;
    private LocalDateTime validStartTime;
    private LocalDateTime validEndTime;
}
