package com.sky;

import com.sky.config.RabbitMQConfiguration;
import com.sky.entity.OrderCouponSnapshot;
import com.sky.entity.OrderRefundRecord;
import com.sky.entity.Orders;
import com.sky.entity.UserCoupon;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderRefundMapper;
import com.sky.service.CouponOrderPricingService;
import com.sky.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCouponLifecycleTest {
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private OrderRefundMapper orderRefundMapper;
    @Mock
    private CouponOrderPricingService pricingService;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void paymentRedeemsCouponAndSuccessfulRefundRestoresItOnce() {
        Orders order = new Orders();
        order.setId(22L);
        order.setNumber("order-22");
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setVersion(0);
        order.setAmount(new BigDecimal("20.00"));
        when(orderMapper.getByNumberForUpdate("order-22")).thenReturn(order);
        when(orderMapper.markPaySuccess(eq(22L), eq(0), any())).thenAnswer(invocation -> {
            order.setStatus(Orders.TO_BE_CONFIRMED);
            order.setPayStatus(Orders.PAID);
            return 1;
        });

        OrderCouponSnapshot snapshot = new OrderCouponSnapshot();
        snapshot.setOrderId(22L);
        snapshot.setUserCouponId(5L);
        snapshot.setActivityId(7L);
        snapshot.setCouponState(1);
        when(couponMapper.getOrderCouponSnapshot(22L)).thenReturn(snapshot);
        when(couponMapper.useCouponForOrder(eq(22L), any())).thenReturn(1);
        when(couponMapper.updateOrderCouponState(22L, 1, 2)).thenAnswer(invocation -> {
            snapshot.setCouponState(2);
            return 1;
        });
        when(couponMapper.incrementActivityUsedCount(22L)).thenReturn(1);

        orderService.paySuccess("order-22");
        assertEquals(2, snapshot.getCouponState());

        OrderRefundRecord refund = new OrderRefundRecord();
        refund.setId(31L);
        refund.setOutRefundNo("RF22");
        refund.setOrderId(22L);
        refund.setOrderNumber("order-22");
        refund.setRefundAmount(new BigDecimal("20.00"));
        refund.setStatus(0);
        when(orderRefundMapper.getForUpdate("RF22")).thenReturn(refund);
        when(orderRefundMapper.updateStatus(31L, 1, "success")).thenAnswer(invocation -> {
            refund.setStatus(1);
            return 1;
        });
        when(orderMapper.getByIdForUpdate(22L)).thenReturn(order);
        when(orderMapper.markRefunded(22L)).thenAnswer(invocation -> {
            order.setPayStatus(Orders.REFUND);
            return 1;
        });
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setId(5L);
        userCoupon.setValidEndTime(LocalDateTime.now().plusDays(1));
        when(couponMapper.getUserCouponByIdForUpdate(5L)).thenReturn(userCoupon);
        when(couponMapper.restoreCouponAfterRefund(eq(22L), any())).thenReturn(1);
        when(couponMapper.updateOrderCouponState(22L, 2, 4)).thenAnswer(invocation -> {
            snapshot.setCouponState(4);
            return 1;
        });
        when(couponMapper.decrementActivityUsedCount(22L)).thenReturn(1);

        orderService.refundNotification("RF22", "order-22", new BigDecimal("20.00"), true, "success");

        assertEquals(Orders.REFUND, order.getPayStatus());
        assertEquals(4, snapshot.getCouponState());
        verify(orderRefundMapper).updateStatus(31L, 1, "success");
        verify(couponMapper).restoreCouponAfterRefund(eq(22L), any());
        verify(couponMapper).decrementActivityUsedCount(22L);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfiguration.ORDER_EXCHANGE),
                eq(RabbitMQConfiguration.ORDER_PAY_ROUTING_KEY),
                eq("order-22"));
    }
}
