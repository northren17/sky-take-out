package com.sky.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderRefundDTO {
    private Long orderId;
    private String requestId;
    private BigDecimal refundAmount;
    private BigDecimal eligibleGoodsRefundAmount;
}
