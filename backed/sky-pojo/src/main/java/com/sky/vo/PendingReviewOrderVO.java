package com.sky.vo;

import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import lombok.Data;

import java.util.List;

@Data
public class PendingReviewOrderVO extends Orders {
    private List<OrderDetail> orderDetails;
}
