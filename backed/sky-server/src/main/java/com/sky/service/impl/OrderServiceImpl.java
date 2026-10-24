package com.sky.service.impl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.CouponOrderPricingService;
import com.sky.service.OrderService;
import com.sky.service.PointsService;
import com.sky.service.SetmealStockService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.math.RoundingMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.sky.config.RabbitMQConfiguration;

/**
 * 订单
 */
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CouponMapper couponMapper;
    @Autowired
    private OrderRefundMapper orderRefundMapper;
    @Autowired
    private CouponOrderPricingService couponOrderPricingService;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private SetmealStockService setmealStockService;
    @Autowired
    private PointsService pointsService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Value("${sky.shop.address}")
    private String shopAddress;

    @Value("${sky.baidu.ak}")
    private String ak;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //异常情况的处理（收货地址为空、超出配送范围、购物车为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        checkOutOfRange(addressBook.getCityName()+addressBook.getDistrictName()+addressBook.getDetail());
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        //查询当前用户的购物车数据
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        for (ShoppingCart cartItem : shoppingCartList) {
            if (cartItem.getSetmealId() != null) {
                setmealStockService.deduct(cartItem.getSetmealId(), cartItem.getNumber());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        BigDecimal itemsAmount = couponOrderPricingService.itemsAmount(shoppingCartList);
        BigDecimal packFee = couponOrderPricingService.packFee(shoppingCartList);
        BigDecimal deliveryFee = couponOrderPricingService.deliveryFee();
        BigDecimal couponDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        UserCoupon selectedCoupon = null;
        if (ordersSubmitDTO.getUserCouponId() != null) {
            selectedCoupon = couponMapper.getUserCouponForUpdate(ordersSubmitDTO.getUserCouponId(), userId);
            if (selectedCoupon == null || selectedCoupon.getStatus() != 0 ||
                    now.isBefore(selectedCoupon.getValidStartTime()) ||
                    !now.isBefore(selectedCoupon.getValidEndTime())) {
                throw new OrderBusinessException("优惠券不存在、已使用或不在有效期内");
            }
            couponDiscount = couponOrderPricingService.discount(selectedCoupon, shoppingCartList);
        }
        BigDecimal payableAmount = itemsAmount.add(packFee).add(deliveryFee).subtract(couponDiscount)
                .setScale(2, RoundingMode.HALF_UP);

        //构造订单数据
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        order.setAmount(payableAmount);
        order.setPackAmount(shoppingCartList.stream().mapToInt(ShoppingCart::getNumber).sum());
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        order.setNumber(String.valueOf(System.currentTimeMillis()));
        order.setUserId(userId);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(LocalDateTime.now());

        //向订单表插入1条数据
        orderMapper.insert(order);

        OrderCouponSnapshot snapshot = new OrderCouponSnapshot();
        snapshot.setOrderId(order.getId());
        snapshot.setItemsAmount(itemsAmount);
        snapshot.setPackFee(packFee);
        snapshot.setDeliveryFee(deliveryFee);
        snapshot.setCouponDiscount(couponDiscount);
        snapshot.setPayableAmount(payableAmount);
        snapshot.setCouponState(0);
        if (selectedCoupon != null) {
            if (couponMapper.lockUserCoupon(selectedCoupon.getId(), userId, order.getId(), now) != 1) {
                throw new OrderBusinessException("优惠券已被其他订单使用");
            }
            snapshot.setUserCouponId(selectedCoupon.getId());
            snapshot.setActivityId(selectedCoupon.getActivityId());
            snapshot.setTemplateId(selectedCoupon.getTemplateId());
            snapshot.setCouponNameSnapshot(selectedCoupon.getCouponNameSnapshot());
            snapshot.setCouponTypeSnapshot(selectedCoupon.getCouponTypeSnapshot());
            snapshot.setThresholdAmountSnapshot(selectedCoupon.getThresholdAmountSnapshot());
            snapshot.setDiscountAmountSnapshot(selectedCoupon.getDiscountAmountSnapshot());
            snapshot.setDiscountRateSnapshot(selectedCoupon.getDiscountRateSnapshot());
            snapshot.setMaxDiscountAmountSnapshot(selectedCoupon.getMaxDiscountAmountSnapshot());
            snapshot.setScopeTypeSnapshot(selectedCoupon.getScopeTypeSnapshot());
            snapshot.setScopeDataSnapshot(selectedCoupon.getScopeDataSnapshot());
            snapshot.setCouponState(1);
        }
        couponMapper.insertOrderCouponSnapshot(snapshot);

        //订单明细数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }

        //向明细表插入n条数据
        orderDetailMapper.insertBatch(orderDetailList);

        //清理购物车中的数据
        shoppingCartMapper.deleteByUserId(userId);

        // 发送延迟消息到 MQ，用于 15 分钟后自动取消未支付订单
        log.info("发送延迟消息到 MQ，订单 ID：{}", order.getId());
        rabbitTemplate.convertAndSend(RabbitMQConfiguration.ORDER_DELAY_EXCHANGE, 
                RabbitMQConfiguration.ORDER_DELAY_ROUTING_KEY, order.getId());

        //封装返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();

        return orderSubmitVO;
    }

    private void releaseSetmealStock(Long orderId) {
        List<OrderDetail> details = orderDetailMapper.getByOrderId(orderId);
        for (OrderDetail detail : details) {
            if (detail.getSetmealId() != null) {
                setmealStockService.restore(detail.getSetmealId(), detail.getNumber());
            }
        }
    }

    @Autowired
    private WeChatPayUtil weChatPayUtil;
    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Long userId = BaseContext.getCurrentId();
        Orders pendingOrder = orderMapper.getByNumberAndUserId(ordersPaymentDTO.getOrderNumber(), userId);
        if (pendingOrder == null) throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        if (!pendingOrder.getStatus().equals(Orders.PENDING_PAYMENT) ||
                !pendingOrder.getPayStatus().equals(Orders.UN_PAID)) {
            throw new OrderBusinessException("订单当前不可支付");
        }

        // --- 修改开始：跳过真实的微信支付 ---
        // 调用微信支付接口，生成预支付交易单
        // JSONObject jsonObject = weChatPayUtil.pay(
        //         ordersPaymentDTO.getOrderNumber(), //商户订单号
        //         new BigDecimal(0.01), //支付金额，单位 元
        //         "苍穹外卖订单", //商品描述
        //         user.getOpenid() //微信用户的openid
        // );

        // if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
        //     throw new OrderBusinessException("该订单已支付");
        // }

        // 模拟支付成功：复用支付回调逻辑，统一处理状态更新和来单提醒
        paySuccess(ordersPaymentDTO.getOrderNumber());
        // --- 修改结束 ---

        // --- 修改开始：模拟返回给前端的支付成功数据 ---
        // 为了能让前端正常跳转，我们模拟一个成功的响应
        JSONObject mockJsonObject = new JSONObject();
        mockJsonObject.put("code", "ORDERPAID"); // 模拟成功码
        mockJsonObject.put("nonceStr", "mockNonceStr");
        mockJsonObject.put("package", "mockPackage");
        mockJsonObject.put("paySign", "mockPaySign");
        mockJsonObject.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
        mockJsonObject.put("signType", "MD5");

        OrderPaymentVO vo = mockJsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(mockJsonObject.getString("package"));
        // --- 修改结束 ---

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(String outTradeNo) {
        // 支付回调场景无登录态，按订单号直接查询
        Orders ordersDB = orderMapper.getByNumberForUpdate(outTradeNo);
        if (ordersDB == null) {
            throw new OrderBusinessException("支付回调处理失败：订单不存在");
        }

        // 幂等性校验：如果订单已经是“待接单”或之后的处理状态，说明已经处理过支付回调，直接返回
        if (ordersDB.getPayStatus().equals(Orders.PAID)) {
            log.info("订单{}已处理过支付回调，无需重复处理", outTradeNo);
            return;
        }
        if (!ordersDB.getStatus().equals(Orders.PENDING_PAYMENT) ||
                !ordersDB.getPayStatus().equals(Orders.UN_PAID)) {
            throw new OrderBusinessException("订单状态不允许支付成功");
        }

        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markPaySuccess(ordersDB.getId(), ordersDB.getVersion(), now) != 1) {
            throw new OrderBusinessException("订单支付状态已变化，请重试");
        }
        markCouponUsed(ordersDB.getId(), now);
        pointsService.recordPendingForPaidOrder(ordersDB.getId(), ordersDB.getUserId());

        // 发送消息到 MQ，异步处理支付成功后的逻辑（如 WebSocket 通知、统计等）
        rabbitTemplate.convertAndSend(RabbitMQConfiguration.ORDER_EXCHANGE, 
                RabbitMQConfiguration.ORDER_PAY_ROUTING_KEY, outTradeNo);
        
        log.info("支付成功消息已发送到 MQ，订单号：{}", outTradeNo);
    }

    private void markCouponUsed(Long orderId, LocalDateTime now) {
        OrderCouponSnapshot snapshot = couponMapper.getOrderCouponSnapshot(orderId);
        if (snapshot == null || snapshot.getCouponState() == 0) return;
        if (snapshot.getCouponState() != 1) {
            throw new OrderBusinessException("订单优惠券状态与支付状态不一致");
        }
        if (couponMapper.useCouponForOrder(orderId, now) != 1 ||
                couponMapper.updateOrderCouponState(orderId, 1, 2) != 1) {
            throw new OrderBusinessException("核销优惠券失败");
        }
        if (couponMapper.incrementActivityUsedCount(orderId) != 1) {
            throw new OrderBusinessException("更新活动使用统计失败");
        }
    }

    private void releaseCoupon(Long orderId, LocalDateTime now) {
        OrderCouponSnapshot snapshot = couponMapper.getOrderCouponSnapshot(orderId);
        if (snapshot == null || snapshot.getCouponState() == 0) return;
        if (snapshot.getCouponState() != 1) {
            throw new OrderBusinessException("订单优惠券状态与取消状态不一致");
        }
        if (couponMapper.releaseCouponForOrder(orderId, now) != 1 ||
                couponMapper.updateOrderCouponState(orderId, 1, 3) != 1) {
            throw new OrderBusinessException("释放锁定优惠券失败");
        }
    }

    private RefundSubmission prepareFullRefund(Orders order) {
        BigDecimal amountRemaining = order.getAmount()
                .subtract(orderRefundMapper.sumPendingAndSucceededRefundAmounts(order.getId()));
        if (amountRemaining.signum() <= 0) return null;
        BigDecimal eligibleAmountRemaining = getEligibleGoodsAmount(order.getId())
                .subtract(orderRefundMapper.sumPendingAndSucceededEligibleRefundAmounts(order.getId()));
        return prepareRefund(order, "ORDER_CANCEL:" + order.getId(), amountRemaining,
                eligibleAmountRemaining);
    }

    @Override
    public void requestPartialRefund(OrderRefundDTO orderRefundDTO) throws Exception {
        if (orderRefundDTO == null || orderRefundDTO.getOrderId() == null ||
                !StringUtils.hasText(orderRefundDTO.getRequestId()) ||
                orderRefundDTO.getRequestId().length() > 64 ||
                orderRefundDTO.getRefundAmount() == null ||
                orderRefundDTO.getEligibleGoodsRefundAmount() == null) {
            throw new OrderBusinessException("退款请求参数不完整");
        }
        BigDecimal refundAmount = normalizeMoney(orderRefundDTO.getRefundAmount());
        BigDecimal eligibleAmount = normalizeMoney(orderRefundDTO.getEligibleGoodsRefundAmount());
        if (refundAmount.signum() <= 0 || eligibleAmount.signum() < 0 ||
                eligibleAmount.compareTo(refundAmount) > 0) {
            throw new OrderBusinessException("退款金额或商品净实付退款金额无效");
        }
        RefundSubmission submission = transactionTemplate.execute(transactionStatus -> {
            Orders order = orderMapper.getByIdForUpdate(orderRefundDTO.getOrderId());
            if (order == null) {
                throw new OrderBusinessException("退款订单不存在");
            }
            if (orderRefundMapper.getByRequestId(orderRefundDTO.getRequestId()) != null) {
                return prepareRefund(order, orderRefundDTO.getRequestId(), refundAmount, eligibleAmount);
            }
            if (!Orders.PAID.equals(order.getPayStatus()) ||
                    Orders.PENDING_PAYMENT.equals(order.getStatus()) ||
                    Orders.CANCELLED.equals(order.getStatus())) {
                throw new OrderBusinessException("当前订单状态不允许部分退款");
            }
            return prepareRefund(order, orderRefundDTO.getRequestId(), refundAmount, eligibleAmount);
        });
        submitRefundIfNew(submission);
    }

    private RefundSubmission prepareRefund(Orders order, String requestId, BigDecimal refundAmount,
                                           BigDecimal eligibleGoodsRefundAmount) {
        OrderRefundRecord previous = orderRefundMapper.getByRequestId(requestId);
        if (previous != null) {
            if (!order.getId().equals(previous.getOrderId()) ||
                    previous.getRefundAmount().compareTo(refundAmount) != 0 ||
                    previous.getEligibleGoodsRefundAmount().compareTo(eligibleGoodsRefundAmount) != 0) {
                throw new OrderBusinessException("退款幂等请求号已用于不同退款参数");
            }
            if (previous.getStatus() == 2) {
                throw new OrderBusinessException("该退款请求此前失败，请使用新的请求号重试");
            }
            return new RefundSubmission(previous, false);
        }

        BigDecimal amountRemaining = order.getAmount()
                .subtract(orderRefundMapper.sumPendingAndSucceededRefundAmounts(order.getId()));
        BigDecimal eligibleAmountRemaining = getEligibleGoodsAmount(order.getId())
                .subtract(orderRefundMapper.sumPendingAndSucceededEligibleRefundAmounts(order.getId()));
        if (refundAmount.compareTo(amountRemaining) == 0 &&
                eligibleGoodsRefundAmount.compareTo(eligibleAmountRemaining) != 0) {
            throw new OrderBusinessException("全额退款时必须计入全部剩余商品净实付金额");
        }
        if (refundAmount.compareTo(amountRemaining) > 0 ||
                eligibleGoodsRefundAmount.compareTo(eligibleAmountRemaining) > 0 ||
                refundAmount.signum() <= 0 || eligibleGoodsRefundAmount.signum() < 0 ||
                eligibleGoodsRefundAmount.compareTo(refundAmount) > 0) {
            throw new OrderBusinessException("累计退款金额超过订单剩余可退款金额");
        }
        String outRefundNo = "RF" + UUID.randomUUID().toString().replace("-", "");
        OrderRefundRecord refund = new OrderRefundRecord();
        refund.setOutRefundNo(outRefundNo);
        refund.setRequestId(requestId);
        refund.setOrderId(order.getId());
        refund.setOrderNumber(order.getNumber());
        refund.setRefundAmount(refundAmount);
        refund.setEligibleGoodsRefundAmount(eligibleGoodsRefundAmount);
        refund.setStatus(0);
        if (orderRefundMapper.insert(refund) != 1) {
            throw new OrderBusinessException("保存退款申请失败");
        }
        return new RefundSubmission(refund, true);
    }

    private void submitRefundIfNew(RefundSubmission submission) throws Exception {
        if (submission == null || !submission.isNew()) return;
        OrderRefundRecord refund = submission.getRefund();
        Orders order = orderMapper.getByid(refund.getOrderId());
        if (order == null) throw new OrderBusinessException("退款单关联订单不存在");

        String responseBody;
        try {
            responseBody = weChatPayUtil.refund(
                    order.getNumber(), refund.getOutRefundNo(), refund.getRefundAmount(), order.getAmount());
        } catch (Exception exception) {
            persistRefundResponse(refund.getId(), "REQUEST_ERROR: " + exception.getMessage());
            throw exception;
        }

        JSONObject response;
        try {
            response = JSON.parseObject(responseBody);
        } catch (RuntimeException exception) {
            persistRefundResponse(refund.getId(), responseBody);
            throw exception;
        }
        String refundStatus = response == null ? null : response.getString("status");
        if ("SUCCESS".equals(refundStatus)) {
            transactionTemplate.execute(transactionStatus -> {
                OrderRefundRecord lockedRefund = orderRefundMapper.getForUpdate(refund.getOutRefundNo());
                if (lockedRefund == null) throw new OrderBusinessException("退款申请记录不存在");
                if (lockedRefund.getStatus() == 0) {
                    orderRefundMapper.updateResponse(lockedRefund.getId(), responseBody);
                    completeRefund(lockedRefund, LocalDateTime.now(), responseBody);
                }
                return null;
            });
            return;
        }
        persistRefundResponse(refund.getId(), responseBody == null ? "EMPTY_RESPONSE" : responseBody);
        if ("PROCESSING".equals(refundStatus)) return;
        if (!StringUtils.hasText(refundStatus)) {
            throw new OrderBusinessException("微信退款响应无状态，退款记录已保留待核查");
        }
        if ("CLOSED".equals(refundStatus) || "ABNORMAL".equals(refundStatus)) {
            transactionTemplate.execute(transactionStatus -> {
                orderRefundMapper.updateStatus(refund.getId(), 2, responseBody);
                return null;
            });
            throw new OrderBusinessException("微信退款未受理，退款状态：" + refundStatus);
        }
        throw new OrderBusinessException("微信退款返回未知状态，申请已保留待核查：" + refundStatus);
    }

    private void persistRefundResponse(Long refundId, String responseBody) {
        transactionTemplate.execute(transactionStatus -> {
            orderRefundMapper.updateResponse(refundId, responseBody);
            return null;
        });
    }

    private BigDecimal getEligibleGoodsAmount(Long orderId) {
        OrderCouponSnapshot snapshot = couponMapper.getOrderCouponSnapshot(orderId);
        if (snapshot == null || snapshot.getItemsAmount() == null || snapshot.getCouponDiscount() == null) {
            throw new OrderBusinessException("订单缺少商品价格快照，无法安全计算积分退款金额");
        }
        BigDecimal eligibleAmount = normalizeMoney(snapshot.getItemsAmount().subtract(snapshot.getCouponDiscount()));
        if (eligibleAmount.signum() < 0) {
            throw new OrderBusinessException("订单商品净实付金额无效");
        }
        return eligibleAmount;
    }

    private BigDecimal normalizeMoney(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new OrderBusinessException("金额最多保留两位小数");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundNotification(String outRefundNo, String outTradeNo, BigDecimal refundAmount,
                                   boolean success, String notificationBody) {
        OrderRefundRecord refund = orderRefundMapper.getForUpdate(outRefundNo);
        if (refund == null) throw new OrderBusinessException("退款通知对应的退款单不存在");
        if (!refund.getOrderNumber().equals(outTradeNo) ||
                refund.getRefundAmount().compareTo(refundAmount) != 0) {
            throw new OrderBusinessException("退款通知订单号或金额与退款记录不匹配");
        }
        if (refund.getStatus() == 1) {
            if (success) return;
            throw new OrderBusinessException("退款通知状态与已完成退款记录冲突");
        }
        if (refund.getStatus() == 2) {
            if (!success) return;
            throw new OrderBusinessException("退款通知状态与已失败退款记录冲突");
        }
        if (refund.getStatus() != 0) {
            throw new OrderBusinessException("退款单已处于终态，拒绝重复变更");
        }
        if (!success) {
            orderRefundMapper.updateStatus(refund.getId(), 2, notificationBody);
            log.error("订单退款失败，orderId={}, refundNo={}", refund.getOrderId(), outRefundNo);
            return;
        }
        completeRefund(refund, LocalDateTime.now(), notificationBody);
    }

    private void completeRefund(OrderRefundRecord refund, LocalDateTime now, String responseBody) {
        if (refund.getStatus() == 1) return;
        Orders order = orderMapper.getByIdForUpdate(refund.getOrderId());
        if (order == null) throw new OrderBusinessException("退款单关联订单不存在");
        if (!Orders.PAID.equals(order.getPayStatus()) && !Orders.REFUND.equals(order.getPayStatus())) {
            throw new OrderBusinessException("订单支付状态不允许退款完成");
        }
        if (orderRefundMapper.updateStatus(refund.getId(), 1, responseBody) != 1) {
            throw new OrderBusinessException("更新退款状态失败");
        }
        BigDecimal totalRefunded = orderRefundMapper.sumSucceededRefundAmounts(order.getId());
        if (totalRefunded.compareTo(order.getAmount()) > 0) {
            throw new OrderBusinessException("累计成功退款金额超过订单实付金额");
        }
        boolean fullyRefunded = totalRefunded.compareTo(order.getAmount()) == 0;
        if (fullyRefunded && order.getPayStatus().equals(Orders.PAID)) {
            if (orderMapper.markRefunded(order.getId()) != 1) {
                throw new OrderBusinessException("更新订单退款状态失败");
            }
        }
        pointsService.adjustForRefund(refund);
        if (fullyRefunded && Orders.CANCELLED.equals(order.getStatus())) {
            releaseSetmealStock(order.getId());
        }

        if (!fullyRefunded) return;
        OrderCouponSnapshot snapshot = couponMapper.getOrderCouponSnapshot(order.getId());
        if (snapshot == null || snapshot.getCouponState() == 0) return;
        if (snapshot.getCouponState() != 2) {
            if (snapshot.getCouponState() == 4 || snapshot.getCouponState() == 5) return;
            throw new OrderBusinessException("退款时订单优惠券状态不正确");
        }
        UserCoupon userCoupon = couponMapper.getUserCouponByIdForUpdate(snapshot.getUserCouponId());
        if (userCoupon == null || couponMapper.restoreCouponAfterRefund(order.getId(), now) != 1) {
            throw new OrderBusinessException("退款成功后返还优惠券失败");
        }
        boolean couponExpired = !userCoupon.getValidEndTime().isAfter(now);
        int nextCouponState = couponExpired ? 5 : 4;
        if (couponMapper.updateOrderCouponState(order.getId(), 2, nextCouponState) != 1 ||
                couponMapper.decrementActivityUsedCount(order.getId()) != 1) {
            throw new OrderBusinessException("退款后更新优惠券统计失败");
        }
    }

    private void applyCouponSnapshot(OrderVO orderVO, Long orderId) {
        OrderCouponSnapshot snapshot = couponMapper.getOrderCouponSnapshot(orderId);
        if (snapshot == null) return;
        orderVO.setItemsAmount(snapshot.getItemsAmount());
        orderVO.setPackFee(snapshot.getPackFee());
        orderVO.setDeliveryFee(snapshot.getDeliveryFee());
        orderVO.setCouponDiscount(snapshot.getCouponDiscount());
        orderVO.setCouponNameSnapshot(snapshot.getCouponNameSnapshot());
    }

    /**
     * 用户端订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }
    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        // 根据id查询订单
        Orders orders = orderMapper.getByid(id);

        // 查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将该订单及其详情封装到OrderVO并返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        applyCouponSnapshot(orderVO, id);

        return orderVO;
    }

    @Override
    public OrderVO detailsForUser(Long id) {
        Orders order = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (order == null) throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        List<OrderDetail> details = orderDetailMapper.getByOrderId(order.getId());
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        orderVO.setOrderDetailList(details);
        applyCouponSnapshot(orderVO, order.getId());
        return orderVO;
    }
    /**
     * 用户取消订单
     *
     * @param id
     */
    public void userCancelById(Long id) throws Exception {
        cancelOrder(id, BaseContext.getCurrentId(), "用户取消");
    }

    @Override
    public void adminCancelById(Long id) throws Exception {
        cancelOrder(id, null, "商家取消");
    }

    private void cancelOrder(Long id, Long expectedUserId, String reason) throws Exception {
        RefundSubmission submission = transactionTemplate.execute(transactionStatus -> {
            Orders order = orderMapper.getByIdForUpdate(id);
            if (order == null) throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
            if (expectedUserId != null && !expectedUserId.equals(order.getUserId())) {
                throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
            }
            LocalDateTime now = LocalDateTime.now();
            if (order.getStatus().equals(Orders.PENDING_PAYMENT) &&
                    order.getPayStatus().equals(Orders.UN_PAID)) {
                if (orderMapper.cancelPendingPayment(id, order.getVersion(), reason, now) != 1) {
                    throw new OrderBusinessException("订单状态已变化，请刷新后重试");
                }
                releaseSetmealStock(id);
                releaseCoupon(id, now);
                return null;
            }
            if (!order.getStatus().equals(Orders.TO_BE_CONFIRMED) ||
                    !order.getPayStatus().equals(Orders.PAID)) {
                throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
            }
            Orders cancelled = Orders.builder()
                    .id(order.getId())
                    .status(Orders.CANCELLED)
                    .cancelReason(reason)
                    .cancelTime(now)
                    .version(order.getVersion())
                    .build();
            if (orderMapper.update(cancelled) != 1) {
                throw new OrderBusinessException("订单状态已变化，请刷新后重试");
            }
            return prepareFullRefund(order);
        });
        submitRefundIfNew(submission);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void timeoutCancel(Long orderId) {
        Orders order = orderMapper.getByIdForUpdate(orderId);
        if (order == null || !order.getStatus().equals(Orders.PENDING_PAYMENT) ||
                !order.getPayStatus().equals(Orders.UN_PAID)) return;
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.cancelPendingPayment(orderId, order.getVersion(), "订单超时未支付，自动取消", now) == 1) {
            releaseSetmealStock(orderId);
            releaseCoupon(orderId, now);
        }
    }
    /**
     * 再来一单
     *
     * @param id
     */
    public void repetition(Long id) {
        // 查询当前用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 将订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 将原订单详情里面的菜品信息重新复制到购物车对象中
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 将购物车对象批量添加到数据库
        shoppingCartMapper.insertBatch(shoppingCartList);
    }
    /**
     * 订单搜索
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        // 需要返回订单菜品信息，自定义OrderVO响应结果
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();
        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : ordersList) {
                // 将共同字段复制到OrderVO
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDishes = getOrderDishesStr(orders);
                applyCouponSnapshot(orderVO, orders.getId());

                // 将订单菜品信息封装到orderVO中，并添加到orderVOList
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    /**
     * 根据订单id获取菜品信息字符串
     *
     * @param orders
     * @return
     */
    private String getOrderDishesStr(Orders orders) {
        // 查询订单菜品详情信息（订单中的菜品和数量）
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", orderDishList);  //分隔符是 "" (空字符串)：意思就是“中间不要任何符号，紧紧挨着”列表是 orderDishList：里面装的是 ["宫保鸡丁*3;", "米饭*2;"]。
    }
    /**
     * 各个状态的订单数量统计
     *
     * @return
     */
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }
    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders ordersDB = orderMapper.getByid(ordersConfirmDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .version(ordersDB.getVersion())
                .build();

        orderMapper.update(orders);
    }
    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        RefundSubmission submission = transactionTemplate.execute(transactionStatus -> {
            Orders order = orderMapper.getByIdForUpdate(ordersRejectionDTO.getId());
            if (order == null || !order.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
                throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
            }
            if (!order.getPayStatus().equals(Orders.PAID)) {
                throw new OrderBusinessException("待接单订单尚未支付，不能按已支付拒单流程处理");
            }
            LocalDateTime now = LocalDateTime.now();
            Orders rejected = new Orders();
            rejected.setId(order.getId());
            rejected.setStatus(Orders.CANCELLED);
            rejected.setRejectionReason(ordersRejectionDTO.getRejectionReason());
            rejected.setCancelTime(now);
            rejected.setVersion(order.getVersion());
            if (orderMapper.update(rejected) != 1) {
                throw new OrderBusinessException("订单状态已变化，请刷新后重试");
            }
            return prepareFullRefund(order);
        });
        submitRefundIfNew(submission);
    }
    /**
     * 派送订单
     *
     * @param id
     */
    public void delivery(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getByid(id);

        // 校验订单是否存在，并且状态为3
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为派送中
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orders.setVersion(ordersDB.getVersion());

        orderMapper.update(orders);
    }
    /**
     * 完成订单
     *
     * @param id
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id) {
        Orders ordersDB = orderMapper.getByIdForUpdate(id);

        if (ordersDB == null || !Orders.DELIVERY_IN_PROGRESS.equals(ordersDB.getStatus()) ||
                !Orders.PAID.equals(ordersDB.getPayStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());
        orders.setVersion(ordersDB.getVersion());

        if (orderMapper.update(orders) != 1) {
            throw new OrderBusinessException("订单状态已变化，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releasePoints(Long orderId) {
        Orders order = orderMapper.getByIdForUpdate(orderId);
        if (order == null || !Orders.COMPLETED.equals(order.getStatus()) ||
                !Orders.PAID.equals(order.getPayStatus())) {
            throw new OrderBusinessException("仅已完成且仍为已支付状态的订单可以审核释放积分");
        }
        if (orderRefundMapper.countPendingRefunds(orderId) > 0) {
            throw new OrderBusinessException("订单存在处理中退款，暂不能释放积分");
        }
        pointsService.releasePendingPoints(orderId);
    }
    /**
     * 检查客户的收货地址是否超出配送范围
     * @param address
     */
    private void checkOutOfRange(String address) {
        Map map = new HashMap();
        map.put("address",shopAddress);
        map.put("output","json");
        map.put("ak",ak);

        //获取店铺的经纬度坐标
        String shopCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);
//jsonobject:无序的键值对集合
        JSONObject jsonObject = JSON.parseObject(shopCoordinate);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("店铺地址解析失败");
        }

        //数据解析
        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");
        //店铺经纬度坐标
        String shopLngLat = lat + "," + lng;

        map.put("address",address);
        //获取用户收货地址的经纬度坐标
        String userCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        jsonObject = JSON.parseObject(userCoordinate);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("收货地址解析失败");
        }

        //数据解析
        location = jsonObject.getJSONObject("result").getJSONObject("location");
        lat = location.getString("lat");
        lng = location.getString("lng");
        //用户收货地址经纬度坐标
        String userLngLat = lat + "," + lng;

        map.put("origin",shopLngLat);//起点
        map.put("destination",userLngLat);//终点
        map.put("steps_info","0");//详细步骤：如果设为 1，百度会返回非常详细的数据，设为 0，百度就只返回一个最核心的数字：距离（distance）
        //路线规划
        String json = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/driving", map);

        jsonObject = JSON.parseObject(json);
        if(!jsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("配送路线规划失败");
        }

        //数据解析
        JSONObject result = jsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) result.get("routes");
        Integer distance = (Integer) ((JSONObject) jsonArray.get(0)).get("distance");
        log.info("配送距离为{}米",distance);

        if(distance > 5000){
            //配送距离超过5000米
            throw new OrderBusinessException("超出配送范围");
        }
    }
    /**
     * 用户催单
     *
     * @param id
     */
    public void reminder(Long id) {
        // 查询订单是否存在
        Orders orders = orderMapper.getByid(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 基于Redis实现催单限流，60秒内只能催单一次
        String remindKey = "remind:order:" + id;
        Boolean success = redisTemplate.opsForValue().setIfAbsent(remindKey, "1", 60, TimeUnit.SECONDS);
        if (success == null || !success) {
            throw new OrderBusinessException("催单过于频繁，请稍后再试");
        }

        //基于WebSocket实现催单
        Map map = new HashMap();
        map.put("type", 2);//2代表用户催单
        map.put("orderId", id);
        map.put("content", "订单号：" + orders.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }

    private static class RefundSubmission {
        private final OrderRefundRecord refund;
        private final boolean isNew;

        private RefundSubmission(OrderRefundRecord refund, boolean isNew) {
            this.refund = refund;
            this.isNew = isNew;
        }

        private OrderRefundRecord getRefund() {
            return refund;
        }

        private boolean isNew() {
            return isNew;
        }
    }

}