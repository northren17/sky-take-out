package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponClaimRecord {
    private Long id;
    private String businessNo;
    private Long activityId;
    private Long userId;
    private Integer status;
    private Long userCouponId;
    private String failureReason;
    private Integer dispatchAttempts;
    private LocalDateTime nextDispatchTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
