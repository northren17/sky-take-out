package com.sky.service;

import com.sky.dto.*;
import com.sky.result.PageResult;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;

import java.math.BigDecimal;

public interface OrderService {

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO);
    /**
     * 订单支付
     * @param ordersPaymentDTO
     * @return
     */
    OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception;

    /**
     * 支付成功，修改订单状态
     * @param outTradeNo
     */
    void paySuccess(String outTradeNo);
    void refundNotification(String outRefundNo, String outTradeNo, BigDecimal refundAmount,
                            boolean success, String notificationBody);
    void timeoutCancel(Long orderId);
    /**
     * 用户端订单分页查询
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    PageResult pageQuery4User(int page, int pageSize, Integer status);
    /**
     * 查询订单详情
     * @param id
     * @return
     */
    OrderVO details(Long id);
    OrderVO detailsForUser(Long id);
    /**
     * 用户取消订单
     * @param id
     */
    void userCancelById(Long id) throws Exception;
    void adminCancelById(Long id) throws Exception;
    /**
     * 再来一单
     *
     * @param id
     */
    void repetition(Long id);
    /**
     * 条件搜索订单
     * @param ordersPageQueryDTO
     * @return
     */
    PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO);
    /**
     * 各个状态的订单数量统计
     * @return
     */
    OrderStatisticsVO statistics();
    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    void confirm(OrdersConfirmDTO ordersConfirmDTO);
    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception;
    /**
     * 派送订单
     *
     * @param id
     */
    void delivery(Long id);
    /**
     * 完成订单
     *
     * @param id
     */
    void complete(Long id);
    /**
     * 用户催单
     * @param id
     */
    void reminder(Long id);
}