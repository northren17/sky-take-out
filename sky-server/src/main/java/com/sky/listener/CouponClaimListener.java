package com.sky.listener;

import com.alibaba.fastjson.JSON;
import com.sky.config.RabbitMQConfiguration;
import com.sky.dto.CouponClaimMessage;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponTemplate;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimRedisService;
import com.sky.service.impl.CouponClaimPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class CouponClaimListener {
    private final CouponMapper couponMapper;
    private final CouponClaimPersistenceService persistenceService;
    private final CouponClaimRedisService claimRedisService;

    @RabbitListener(queues = RabbitMQConfiguration.COUPON_CLAIM_QUEUE,
            containerFactory = "couponClaimListenerContainerFactory")
    public void processClaim(String body) {
        CouponClaimMessage message = JSON.parseObject(body, CouponClaimMessage.class);
        if (message == null || message.getBusinessNo() == null ||
                message.getActivityId() == null || message.getUserId() == null) {
            throw new IllegalArgumentException("优惠券领取消息缺少必要字段");
        }
        CouponClaimRecord record = couponMapper.getClaimByBusinessNo(message.getBusinessNo());
        if (record == null) {
            throw new IllegalStateException("优惠券领取消息没有对应的持久化流水");
        }
        if (!record.getActivityId().equals(message.getActivityId()) ||
                !record.getUserId().equals(message.getUserId())) {
            throw new IllegalStateException("优惠券领取消息与持久化流水不匹配");
        }
        if (record.getStatus() == 1) {
            clearReservation(record);
            return;
        }
        if (record.getStatus() == 2) return;

        CouponActivity activity = couponMapper.getActivity(record.getActivityId());
        CouponTemplate template = activity == null ? null : couponMapper.getTemplate(activity.getTemplateId());
        if (activity == null || template == null) {
            compensateAndFail(record, "领取任务关联的活动或优惠券模板不存在");
            return;
        }

        Long userCouponId = persistenceService.completeClaim(
                record, activity, template, LocalDateTime.now());
        if (userCouponId == null) {
            throw new IllegalStateException("优惠券领取已处理但未返回用户优惠券编号");
        }
        clearReservation(record);
        log.info("优惠券领取消息处理成功，businessNo={}, userCouponId={}",
                record.getBusinessNo(), userCouponId);
    }

    private void compensateAndFail(CouponClaimRecord record, String reason) {
        claimRedisService.compensateReservation(
                record.getActivityId(), record.getUserId(), record.getBusinessNo());
        persistenceService.markFailed(record.getId(), reason);
    }

    private void clearReservation(CouponClaimRecord record) {
        try {
            if (!claimRedisService.completeReservation(
                    record.getActivityId(), record.getUserId(), record.getBusinessNo())) {
                log.warn("领取消息完成后Redis预占标记不存在，businessNo={}", record.getBusinessNo());
            }
        } catch (RuntimeException cleanupFailure) {
            log.error("领取消息已持久化但Redis预占标记清理失败，businessNo={}",
                    record.getBusinessNo(), cleanupFailure);
        }
    }
}
