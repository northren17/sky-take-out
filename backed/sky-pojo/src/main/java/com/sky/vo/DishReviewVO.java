package com.sky.vo;

import com.sky.entity.OrderReviewImage;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DishReviewVO {
    private Long reviewId;
    private Long orderId;
    private String userName;
    private Integer rating;
    private String content;
    private LocalDateTime createTime;
    private List<OrderReviewImage> images;
}
