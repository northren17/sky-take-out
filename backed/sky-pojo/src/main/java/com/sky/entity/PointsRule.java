package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PointsRule {
    private Long id;
    private Integer version;
    private BigDecimal spendStepAmount;
    private Integer pointsPerStep;
    private Integer status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String description;
    private Long createUser;
    private Long updateUser;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
