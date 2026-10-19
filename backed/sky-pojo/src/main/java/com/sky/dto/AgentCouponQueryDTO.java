package com.sky.dto;

import lombok.Data;

@Data
public class AgentCouponQueryDTO {
    private String sessionCapability;
    private Integer page;
    private Integer pageSize;
    private Integer status;
}
