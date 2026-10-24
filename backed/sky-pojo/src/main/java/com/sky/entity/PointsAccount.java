package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PointsAccount {
    private Long id;
    private Long userId;
    private Long balancePoints;
    private Long totalEarned;
    private Long totalSpent;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
