package com.sky.service;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.vo.AgentCouponSummaryVO;
import com.sky.vo.AgentOrderSummaryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBusinessQueryServiceTest {
    private OrderMapper orderMapper;
    private OrderDetailMapper orderDetailMapper;
    private CouponMapper couponMapper;
    private AgentBusinessQueryService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderDetailMapper = mock(OrderDetailMapper.class);
        couponMapper = mock(CouponMapper.class);
        service = new AgentBusinessQueryService(orderMapper, orderDetailMapper, couponMapper);
    }

    @AfterEach
    void clearPageHelperContext() {
        PageHelper.clearPage();
    }

    @Test
    void recentOrdersUseBoundedUserScopedQueryAndReturnOnlySummaryFields() {
        Long userId = 9123L;
        Orders order = Orders.builder()
                .id(8L)
                .number("O-1008")
                .status(5)
                .orderTime(LocalDateTime.now().minusDays(2))
                .amount(new BigDecimal("23.50"))
                .phone("13800000000")
                .address("private address")
                .remark("private note")
                .build();
        OrderDetail detail = OrderDetail.builder()
                .id(90L)
                .orderId(8L)
                .name("noodle")
                .number(2)
                .image("private-image-url")
                .dishFlavor("private flavor")
                .build();
        when(orderMapper.listRecentForAgent(eq(userId), any(), any(), eq(10)))
                .thenReturn(List.of(order));
        when(orderDetailMapper.getByOrderId(8L)).thenReturn(List.of(detail));

        List<AgentOrderSummaryVO> result = service.recentOrders(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOrderNumber()).isEqualTo("O-1008");
        assertThat(result.getFirst().getItems()).hasSize(1);
        assertThat(result.getFirst().getItems().getFirst().getName()).isEqualTo("noodle");
        assertThat(result.getFirst().getItems().getFirst().getQuantity()).isEqualTo(2);
        assertThat(result.getFirst().toString()).doesNotContain(
                "13800000000", "private address", "private note", "private-image-url");
        verify(orderMapper).listRecentForAgent(eq(userId), any(), any(), eq(10));
    }

    @Test
    void couponQueryRequiresValidPaginationAndUsesAuthenticatedUserId() {
        Long userId = 9123L;
        Page<AgentCouponSummaryVO> couponPage = new Page<>(2, 10);
        couponPage.setTotal(13);
        when(couponMapper.pageAgentCoupons(eq(userId), eq(1), any())).thenReturn(couponPage);

        var result = service.coupons(userId, 2, 10, 1);

        assertThat(result.getTotal()).isEqualTo(13);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        verify(couponMapper).pageAgentCoupons(eq(userId), eq(1), any());
        assertThatThrownBy(() -> service.coupons(userId, 1, 51, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.coupons(userId, 1, 10, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
