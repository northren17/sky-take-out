package com.sky.service;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.vo.AgentCouponPageVO;
import com.sky.vo.AgentCouponSummaryVO;
import com.sky.vo.AgentOrderItemVO;
import com.sky.vo.AgentOrderSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentBusinessQueryService {
    private static final int RECENT_ORDER_DAYS = 90;
    private static final int MAX_RECENT_ORDERS = 10;
    private static final int MAX_COUPON_PAGE_SIZE = 50;

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final CouponMapper couponMapper;

    public List<AgentOrderSummaryVO> recentOrders(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Orders> orders = orderMapper.listRecentForAgent(
                userId, now.minusDays(RECENT_ORDER_DAYS), now, MAX_RECENT_ORDERS);
        List<AgentOrderSummaryVO> result = new ArrayList<>(orders.size());
        for (Orders order : orders) {
            AgentOrderSummaryVO summary = new AgentOrderSummaryVO();
            summary.setOrderNumber(order.getNumber());
            summary.setStatus(order.getStatus());
            summary.setCreatedAt(order.getOrderTime());
            summary.setAmount(order.getAmount());
            List<AgentOrderItemVO> items = new ArrayList<>();
            for (OrderDetail detail : orderDetailMapper.getByOrderId(order.getId())) {
                AgentOrderItemVO item = new AgentOrderItemVO();
                item.setName(detail.getName());
                item.setQuantity(detail.getNumber());
                items.add(item);
            }
            summary.setItems(items);
            result.add(summary);
        }
        return result;
    }

    public AgentCouponPageVO coupons(Long userId, Integer requestedPage,
                                     Integer requestedPageSize, Integer status) {
        int page = requestedPage == null ? 1 : requestedPage;
        int pageSize = requestedPageSize == null ? 20 : requestedPageSize;
        if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > MAX_COUPON_PAGE_SIZE) {
            throw new IllegalArgumentException("优惠券分页参数无效");
        }
        if (status != null && (status < 0 || status > 3)) {
            throw new IllegalArgumentException("优惠券状态无效");
        }
        PageHelper.startPage(page, pageSize);
        Page<AgentCouponSummaryVO> coupons = couponMapper.pageAgentCoupons(
                userId, status, LocalDateTime.now());
        return new AgentCouponPageVO(coupons.getTotal(), page, pageSize, coupons.getResult());
    }
}
