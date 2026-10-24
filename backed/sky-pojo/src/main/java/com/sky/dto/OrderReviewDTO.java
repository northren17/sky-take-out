package com.sky.dto;

import lombok.Data;

import java.util.List;

@Data
public class OrderReviewDTO {
    private Long orderId;
    private Integer rating;
    private String content;
    private List<String> images;
    private List<ReviewItemDTO> items;
}
