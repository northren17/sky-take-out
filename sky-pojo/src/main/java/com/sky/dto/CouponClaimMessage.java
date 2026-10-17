package com.sky.dto;

import lombok.Data;

@Data
public class CouponClaimMessage {
    private String businessNo;
    private Long activityId;
    private Long userId;
}
