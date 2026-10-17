package com.sky.listener;

import com.alibaba.fastjson.JSON;
import com.sky.config.RabbitMQConfiguration;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderService;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class OrderListener {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 监听支付成功消息，处理异步逻辑
     */
    @RabbitListener(queues = RabbitMQConfiguration.ORDER_PAY_QUEUE)
    public void processOrderPay(String outTradeNo) {
        log.info("收到支付成功 MQ 消息，订单号：{}", outTradeNo);

        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB != null) {
            // 通过 WebSocket 实现来单提醒
            Map<String, Object> map = new HashMap<>();
            map.put("type", 1); // 1表示来单提醒
            map.put("orderId", ordersDB.getId());
            map.put("content", "订单号：" + outTradeNo);

            try {
                webSocketServer.sendToAllClient(JSON.toJSONString(map));
                log.info("已通过 WebSocket 推送来单提醒，订单号：{}", outTradeNo);
            } catch (Exception e) {
                log.error("来单提醒推送失败，订单号：{}", outTradeNo, e);
            }
        }
    }

    /**
     * 监听超时取消消息
     */
    @RabbitListener(queues = RabbitMQConfiguration.ORDER_CANCEL_QUEUE)
    public void processOrderCancel(Long orderId) {
        log.info("收到订单超时 MQ 消息，订单 ID：{}", orderId);

        orderService.timeoutCancel(orderId);
        log.info("订单 ID：{} 超时取消检查完成", orderId);
    }
}
