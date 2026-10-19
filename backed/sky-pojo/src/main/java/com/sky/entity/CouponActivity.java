package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponActivity {
    private Long id;
    private Long templateId;
    private String name;
    private Integer stockTotal;
    private Integer stockRemaining;
    private Integer issuedCount;
    private Integer usedCount;
    private Integer perUserLimit;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long createUser;
    private Long updateUser;
}
