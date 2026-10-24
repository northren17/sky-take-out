package com.sky.service;

import com.sky.entity.OrderRefundRecord;

public interface PointsService {
    void recordPendingForPaidOrder(Long orderId, Long userId);

    void releasePendingPoints(Long orderId);

    void adjustForRefund(OrderRefundRecord refund);
}
