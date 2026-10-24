package com.sky.dto;

import lombok.Data;

@Data
public class ReviewItemDTO {
    private Long orderDetailId;
    private Long dishId;
    private Integer rating;
}
