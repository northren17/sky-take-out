package com.sky;

import com.sky.config.RabbitMQConfiguration;
import com.sky.entity.OrderCouponSnapshot;
import com.sky.entity.OrderDetail;
import com.sky.entity.OrderRefundRecord;
import com.sky.entity.Orders;
import com.sky.entity.UserCoupon;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderRefundMapper;
import com.sky.service.CouponOrderPricingService;
import com.sky.service.PointsService;
import com.sky.service.SetmealStockService;
import com.sky.service.impl.OrderServiceImpl;
import com.sky.dto.OrderRefundDTO;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCouponLifecycleTest {
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderDetailMapper orderDetailMapper;
    @Mock
    private SetmealStockService setmealStockService;
    @Mock
    private PointsService pointsService;
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private OrderRefundMapper orderRefundMapper;
    @Mock
    private CouponOrderPricingService pricingService;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private WeChatPayUtil weChatPayUtil;
    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void paymentRedeemsCouponAndSuccessfulRefundRestoresItOnce() {
        Orders order = new Orders();
        order.setId(22L);
        order.setUserId(7L);
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
        verify(pointsService).recordPendingForPaidOrder(22L, 7L);

        OrderRefundRecord refund = new OrderRefundRecord();
        refund.setId(31L);
        refund.setOutRefundNo("RF22");
        refund.setOrderId(22L);
        refund.setOrderNumber("order-22");
        refund.setRefundAmount(new BigDecimal("20.00"));
        refund.setEligibleGoodsRefundAmount(new BigDecimal("20.00"));
        refund.setStatus(0);
        when(orderRefundMapper.getForUpdate("RF22")).thenReturn(refund);
        when(orderRefundMapper.updateStatus(31L, 1, "success")).thenAnswer(invocation -> {
            refund.setStatus(1);
            return 1;
        });
        when(orderMapper.getByIdForUpdate(22L)).thenReturn(order);
        order.setStatus(Orders.CANCELLED);
        when(orderMapper.markRefunded(22L)).thenAnswer(invocation -> {
            order.setPayStatus(Orders.REFUND);
            return 1;
        });
        when(orderRefundMapper.sumSucceededRefundAmounts(22L)).thenReturn(new BigDecimal("20.00"));
        OrderDetail orderDetail = new OrderDetail();
        orderDetail.setSetmealId(4L);
        orderDetail.setNumber(2);
        when(orderDetailMapper.getByOrderId(22L)).thenReturn(List.of(orderDetail));
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
        verify(pointsService).adjustForRefund(refund);
        verify(setmealStockService).restore(4L, 2);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfiguration.ORDER_EXCHANGE),
                eq(RabbitMQConfiguration.ORDER_PAY_ROUTING_KEY),
                eq("order-22"));
    }

    @Test
    void completingPaidDeliveryOrderDoesNotReleasePointsWithoutManualApproval() {
        Orders order = new Orders();
        order.setId(42L);
        order.setUserId(7L);
        order.setStatus(Orders.DELIVERY_IN_PROGRESS);
        order.setPayStatus(Orders.PAID);
        order.setVersion(3);
        when(orderMapper.getByIdForUpdate(42L)).thenReturn(order);
        when(orderMapper.update(any(Orders.class))).thenReturn(1);

        orderService.complete(42L);

        verify(orderMapper).getByIdForUpdate(42L);
        verify(pointsService, never()).releasePendingPoints(42L);
    }

    @Test
    void refundNotificationAmountMismatchIsRejected() {
        OrderRefundRecord refund = new OrderRefundRecord();
        refund.setId(31L);
        refund.setOutRefundNo("RF22");
        refund.setOrderId(22L);
        refund.setOrderNumber("order-22");
        refund.setRefundAmount(new BigDecimal("20.00"));
        refund.setStatus(0);
        when(orderRefundMapper.getForUpdate("RF22")).thenReturn(refund);

        assertThrows(OrderBusinessException.class,
                () -> orderService.refundNotification("RF22", "order-22",
                        new BigDecimal("10.00"), true, "partial"));

        verify(pointsService, never()).adjustForRefund(any(OrderRefundRecord.class));
        verify(orderMapper, never()).getByIdForUpdate(22L);
    }

    @Test
    void pointsReleaseIsBlockedWhileRefundIsProcessing() {
        Orders order = new Orders();
        order.setId(42L);
        order.setStatus(Orders.COMPLETED);
        order.setPayStatus(Orders.PAID);
        when(orderMapper.getByIdForUpdate(42L)).thenReturn(order);
        when(orderRefundMapper.countPendingRefunds(42L)).thenReturn(1);

        assertThrows(OrderBusinessException.class, () -> orderService.releasePoints(42L));

        verify(pointsService, never()).releasePendingPoints(42L);
    }

    @Test
    void completedPaidOrderCanBeReleasedAfterNoRefundIsPending() {
        Orders order = new Orders();
        order.setId(43L);
        order.setStatus(Orders.COMPLETED);
        order.setPayStatus(Orders.PAID);
        when(orderMapper.getByIdForUpdate(43L)).thenReturn(order);
        when(orderRefundMapper.countPendingRefunds(43L)).thenReturn(0);

        orderService.releasePoints(43L);

        verify(pointsService).releasePendingPoints(43L);
    }

    @Test
    void partialRefundUsesPersistedIdempotencyRecordAndCallsWechatWithOriginalTotal() throws Exception {
        stubTransactionTemplate();
        Orders order = new Orders();
        order.setId(22L);
        order.setNumber("order-22");
        order.setStatus(Orders.COMPLETED);
        order.setPayStatus(Orders.PAID);
        order.setAmount(new BigDecimal("20.00"));
        when(orderMapper.getByIdForUpdate(22L)).thenReturn(order);
        when(orderMapper.getByid(22L)).thenReturn(order);
        when(orderRefundMapper.getByRequestId("request-22-1")).thenReturn(null);
        when(orderRefundMapper.sumPendingAndSucceededRefundAmounts(22L)).thenReturn(BigDecimal.ZERO);
        when(orderRefundMapper.sumPendingAndSucceededEligibleRefundAmounts(22L)).thenReturn(BigDecimal.ZERO);
        OrderCouponSnapshot snapshot = new OrderCouponSnapshot();
        snapshot.setItemsAmount(new BigDecimal("18.00"));
        snapshot.setCouponDiscount(new BigDecimal("3.00"));
        when(couponMapper.getOrderCouponSnapshot(22L)).thenReturn(snapshot);
        when(orderRefundMapper.insert(any(OrderRefundRecord.class))).thenAnswer(invocation -> {
            ((OrderRefundRecord) invocation.getArgument(0)).setId(31L);
            return 1;
        });
        when(orderRefundMapper.updateResponse(31L, "{\"status\":\"PROCESSING\"}")).thenReturn(1);
        when(weChatPayUtil.refund(eq("order-22"), anyString(), eq(new BigDecimal("5.00")),
                eq(new BigDecimal("20.00")))).thenReturn("{\"status\":\"PROCESSING\"}");

        OrderRefundDTO dto = new OrderRefundDTO();
        dto.setOrderId(22L);
        dto.setRequestId("request-22-1");
        dto.setRefundAmount(new BigDecimal("5.00"));
        dto.setEligibleGoodsRefundAmount(new BigDecimal("4.00"));

        orderService.requestPartialRefund(dto);

        ArgumentCaptor<OrderRefundRecord> captor = ArgumentCaptor.forClass(OrderRefundRecord.class);
        verify(orderRefundMapper).insert(captor.capture());
        assertEquals("request-22-1", captor.getValue().getRequestId());
        assertEquals(new BigDecimal("4.00"), captor.getValue().getEligibleGoodsRefundAmount());
        verify(weChatPayUtil).refund(eq("order-22"), anyString(), eq(new BigDecimal("5.00")),
                eq(new BigDecimal("20.00")));
    }

    @Test
    void repeatedPartialRefundRequestDoesNotSubmitAnotherWechatRefund() throws Exception {
        stubTransactionTemplate();
        Orders order = new Orders();
        order.setId(22L);
        order.setNumber("order-22");
        order.setStatus(Orders.COMPLETED);
        order.setPayStatus(Orders.REFUND);
        when(orderMapper.getByIdForUpdate(22L)).thenReturn(order);
        OrderRefundRecord previous = new OrderRefundRecord();
        previous.setOrderId(22L);
        previous.setRequestId("request-22-1");
        previous.setRefundAmount(new BigDecimal("5.00"));
        previous.setEligibleGoodsRefundAmount(new BigDecimal("4.00"));
        previous.setStatus(0);
        when(orderRefundMapper.getByRequestId("request-22-1")).thenReturn(previous);

        OrderRefundDTO dto = new OrderRefundDTO();
        dto.setOrderId(22L);
        dto.setRequestId("request-22-1");
        dto.setRefundAmount(new BigDecimal("5.00"));
        dto.setEligibleGoodsRefundAmount(new BigDecimal("4.00"));

        orderService.requestPartialRefund(dto);

        verify(weChatPayUtil, never()).refund(anyString(), anyString(), any(), any());
        verify(orderRefundMapper, never()).insert(any(OrderRefundRecord.class));
    }

    private void stubTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }
}
