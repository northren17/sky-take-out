package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponExchangeRedemption {
    private Long id;
    private Long exchangeCodeId;
    private Long activityId;
    private String businessNo;
    private Long userId;
    private Integer status;
    private String failureReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
