package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.sky.config.RabbitMQConfiguration;
import com.sky.dto.CouponClaimMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class CouponClaimMessagePublisher {
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    public void publish(CouponClaimMessage payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(payload.getBusinessNo());
        properties.setCorrelationId(payload.getBusinessNo());
        Message message = new Message(JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData(payload.getBusinessNo());
        rabbitTemplate.send(RabbitMQConfiguration.COUPON_CLAIM_EXCHANGE,
                RabbitMQConfiguration.COUPON_CLAIM_ROUTING_KEY, message, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ未确认优惠券领取消息: " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("优惠券领取消息未路由到队列");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待RabbitMQ领取消息确认时线程被中断", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("等待RabbitMQ领取消息确认失败", failure);
        }
    }
}
