package com.sky.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CouponActivityVO {
    private Long id;
    private Long templateId;
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
    private Integer stockRemaining;
    private Integer issuedCount;
    private Integer usedCount;
    private Integer perUserLimit;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer activityStatus;
    private Long claimedByCurrentUser;
}
