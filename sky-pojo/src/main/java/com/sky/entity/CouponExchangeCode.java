package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponExchangeCode {
    private Long id;
    private String code;
    private Long activityId;
    private LocalDateTime expiresAt;
    private Integer maxRedeemCount;
    private Integer redeemedCount;
    private Integer status;
    private LocalDateTime createTime;
}
