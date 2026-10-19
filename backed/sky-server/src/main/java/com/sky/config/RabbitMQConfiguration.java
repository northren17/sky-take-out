package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class RabbitMQConfiguration {

    // 订单交换机
    public static final String ORDER_EXCHANGE = "sky.order.exchange";
    
    // 支付成功队列
    public static final String ORDER_PAY_QUEUE = "sky.order.pay.queue";
    public static final String ORDER_PAY_ROUTING_KEY = "order.pay";

    // 延迟队列配置（用于订单超时自动取消）
    public static final String ORDER_DELAY_EXCHANGE = "sky.order.delay.exchange";
    public static final String ORDER_DELAY_QUEUE = "sky.order.delay.queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";

    public static final String ORDER_CANCEL_QUEUE = "sky.order.cancel.queue";
    public static final String ORDER_CANCEL_ROUTING_KEY = "order.cancel";

    public static final String COUPON_CLAIM_EXCHANGE = "sky.coupon.claim.exchange";
    public static final String COUPON_CLAIM_QUEUE = "sky.coupon.claim.queue";
    public static final String COUPON_CLAIM_ROUTING_KEY = "coupon.claim";
    public static final String COUPON_CLAIM_DLX = "sky.coupon.claim.dlx";
    public static final String COUPON_CLAIM_DLQ = "sky.coupon.claim.dlq";
    public static final String COUPON_CLAIM_DLQ_ROUTING_KEY = "coupon.claim.dead";

    @Bean
    public DirectExchange couponClaimExchange() {
        return new DirectExchange(COUPON_CLAIM_EXCHANGE, true, false);
    }

    @Bean
    public Queue couponClaimQueue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", COUPON_CLAIM_DLX);
        arguments.put("x-dead-letter-routing-key", COUPON_CLAIM_DLQ_ROUTING_KEY);
        return new Queue(COUPON_CLAIM_QUEUE, true, false, false, arguments);
    }

    @Bean
    public Binding couponClaimBinding(Queue couponClaimQueue, DirectExchange couponClaimExchange) {
        return BindingBuilder.bind(couponClaimQueue).to(couponClaimExchange).with(COUPON_CLAIM_ROUTING_KEY);
    }

    @Bean
    public DirectExchange couponClaimDeadLetterExchange() {
        return new DirectExchange(COUPON_CLAIM_DLX, true, false);
    }

    @Bean
    public Queue couponClaimDeadLetterQueue() {
        return new Queue(COUPON_CLAIM_DLQ, true);
    }

    @Bean
    public Binding couponClaimDeadLetterBinding(
            Queue couponClaimDeadLetterQueue, DirectExchange couponClaimDeadLetterExchange) {
        return BindingBuilder.bind(couponClaimDeadLetterQueue)
                .to(couponClaimDeadLetterExchange).with(COUPON_CLAIM_DLQ_ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory couponClaimListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(5);
        factory.setMaxConcurrentConsumers(50);
        factory.setPrefetchCount(1);
        return factory;
    }

    /**
     * 支付成功处理交换机
     */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    /**
     * 支付成功处理队列
     */
    @Bean
    public Queue orderPayQueue() {
        return new Queue(ORDER_PAY_QUEUE);
    }

    /**
     * 绑定支付成功队列到交换机
     */
    @Bean
    public Binding orderPayBinding(Queue orderPayQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderPayQueue).to(orderExchange).with(ORDER_PAY_ROUTING_KEY);
    }

    /**
     * 延迟队列（死信队列方案）
     * 用户下单后进入此队列，等待 TTL 到期
     */
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 绑定死信交换机
        args.put("x-dead-letter-exchange", ORDER_EXCHANGE);
        // 绑定死信路由键
        args.put("x-dead-letter-routing-key", ORDER_CANCEL_ROUTING_KEY);
        // 设置 TTL 为 15 分钟 (900000ms)
        args.put("x-message-ttl", 900000);
        return new Queue(ORDER_DELAY_QUEUE, true, false, false, args);
    }

    /**
     * 实际处理超时的取消队列
     */
    @Bean
    public Queue orderCancelQueue() {
        return new Queue(ORDER_CANCEL_QUEUE);
    }

    /**
     * 绑定取消队列到主交换机
     */
    @Bean
    public Binding orderCancelBinding(Queue orderCancelQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderCancelQueue).to(orderExchange).with(ORDER_CANCEL_ROUTING_KEY);
    }

    /**
     * 延迟交换机
     */
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE);
    }

    /**
     * 绑定延迟队列到延迟交换机
     */
    @Bean
    public Binding orderDelayBinding(Queue orderDelayQueue, DirectExchange orderDelayExchange) {
        return BindingBuilder.bind(orderDelayQueue).to(orderDelayExchange).with(ORDER_DELAY_ROUTING_KEY);
    }
}
