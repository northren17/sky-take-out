package com.sky.vo;

import com.sky.entity.OrderReview;
import com.sky.entity.OrderReviewImage;
import com.sky.entity.OrderReviewItem;
import lombok.Data;

import java.util.List;

@Data
public class OrderReviewVO extends OrderReview {
    private String orderNumber;
    private List<OrderReviewImage> images;
    private List<OrderReviewItem> items;
}
