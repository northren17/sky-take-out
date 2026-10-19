package com.sky.vo;

import lombok.Data;

@Data
public class CouponClaimStatusVO {
    private String businessNo;
    private Integer status;
    private Long userCouponId;
    private String failureReason;
}
