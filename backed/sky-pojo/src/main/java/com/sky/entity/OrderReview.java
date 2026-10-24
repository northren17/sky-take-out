package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderReview {
    private Long id;
    private Long orderId;
    private Long userId;
    private Integer rating;
    private String content;
    private LocalDateTime createTime;
}
