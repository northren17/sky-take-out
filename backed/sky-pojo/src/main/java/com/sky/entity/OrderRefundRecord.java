package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderRefundRecord {
    private Long id;
    private String outRefundNo;
    private Long orderId;
    private String orderNumber;
    private BigDecimal refundAmount;
    private Integer status;
    private String responseBody;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
