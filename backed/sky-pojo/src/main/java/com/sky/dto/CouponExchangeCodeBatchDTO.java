package com.sky.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponExchangeCodeBatchDTO {
    private Long activityId;
    private Integer count;
    private Integer maxRedeemCount;
    private LocalDateTime expiresAt;
}
