package com.sky.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponActivityDTO {
    private Long id;
    private Long templateId;
    private String name;
    private Integer stockTotal;
    private Integer perUserLimit;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
}
