package com.sky.task;

import com.sky.dto.CouponClaimMessage;
import com.sky.entity.CouponClaimRecord;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimMessagePublisher;
import com.sky.service.CouponClaimRedisService;
import com.sky.service.impl.CouponClaimPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CouponClaimOutboxDispatcher {
    private static final int BATCH_SIZE = 100;
    private static final int MAX_DISPATCH_ATTEMPTS = 8;

    private final CouponMapper couponMapper;
    private final CouponClaimRedisService claimRedisService;
    private final CouponClaimMessagePublisher messagePublisher;
    private final CouponClaimPersistenceService claimPersistenceService;

    @Scheduled(fixedDelayString = "${sky.coupon.claim-dispatch-delay-ms:2000}",
            initialDelayString = "${sky.coupon.claim-dispatch-initial-delay-ms:1000}")
    public void dispatchPendingClaims() {
        List<CouponClaimRecord> dueRecords = couponMapper.listDueClaimDispatches(BATCH_SIZE);
        for (CouponClaimRecord record : dueRecords) {
            dispatch(record);
        }
    }

    private void dispatch(CouponClaimRecord record) {
        try {
            String reservedUser = claimRedisService.getReservation(
                    record.getActivityId(), record.getBusinessNo());
            if (reservedUser == null) {
                if (claimPersistenceService.failPendingDispatch(
                        record.getId(), "未找到Redis预占记录，领取请求无法恢复")) {
                    log.warn("领取任务缺少Redis预占，已置为失败，businessNo={}", record.getBusinessNo());
                }
                return;
            }
            if (!record.getUserId().toString().equals(reservedUser)) {
                throw new IllegalStateException("Redis领取预占用户与数据库流水不一致");
            }
            int attempts = record.getDispatchAttempts() == null ? 0 : record.getDispatchAttempts();
            if (attempts >= MAX_DISPATCH_ATTEMPTS) {
                if (claimPersistenceService.failPendingDispatch(
                        record.getId(), "领取消息达到最大投递次数，库存已执行补偿")) {
                    claimRedisService.compensateReservation(
                            record.getActivityId(), record.getUserId(), record.getBusinessNo());
                    log.error("领取消息达到最大投递次数，已关闭任务并补偿Redis，businessNo={}",
                            record.getBusinessNo());
                }
                return;
            }
            int retryDelaySeconds = retryDelaySeconds(attempts);
            if (couponMapper.reserveClaimDispatch(record.getId(), retryDelaySeconds) != 1) return;
            CouponClaimMessage message = new CouponClaimMessage();
            message.setBusinessNo(record.getBusinessNo());
            message.setActivityId(record.getActivityId());
            message.setUserId(record.getUserId());
            messagePublisher.publish(message);
        } catch (RuntimeException dispatchFailure) {
            log.error("优惠券领取消息投递失败，后续将按退避间隔重试，businessNo={}",
                    record.getBusinessNo(), dispatchFailure);
        }
    }

    private int retryDelaySeconds(Integer attempts) {
        int exponent = Math.min(attempts == null ? 0 : attempts, 6);
        return Math.min(10 * (1 << exponent), 300);
    }
}
