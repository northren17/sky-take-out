package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderReviewImage {
    private Long id;
    private Long reviewId;
    private String imageUrl;
    private Integer sort;
    private LocalDateTime createTime;
}
