package com.sky.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AgentOrderSummaryVO {
    private String orderNumber;
    private Integer status;
    private LocalDateTime createdAt;
    private BigDecimal amount;
    private List<AgentOrderItemVO> items;
}
