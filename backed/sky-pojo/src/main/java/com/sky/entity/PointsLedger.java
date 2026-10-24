package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PointsLedger {
    private Long id;
    private Long userId;
    private String businessType;
    private String businessId;
    private String idempotencyKey;
    private Long changePoints;
    private Long balanceBefore;
    private Long balanceAfter;
    private Long ruleId;
    private BigDecimal eligibleAmount;
    private String reason;
    private LocalDateTime createTime;
}
