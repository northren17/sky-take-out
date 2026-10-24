package com.sky.entity;

import lombok.Data;

@Data
public class OrderReviewItem {
    private Long id;
    private Long reviewId;
    private Long orderDetailId;
    private Long dishId;
    private String dishName;
    private Integer rating;
}
