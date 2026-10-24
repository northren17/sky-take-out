package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PointsPendingOrder {
    public static final int PENDING = 0;
    public static final int RELEASED = 1;
    public static final int CANCELLED = 2;

    private Long id;
    private Long orderId;
    private Long userId;
    private Long ruleId;
    private BigDecimal eligibleAmount;
    private BigDecimal refundedEligibleAmount;
    private BigDecimal spendStepAmount;
    private Integer pointsPerStep;
    private Long earnedPoints;
    private Long pendingPoints;
    private Long issuedPoints;
    private Long reversedPoints;
    private Integer status;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
