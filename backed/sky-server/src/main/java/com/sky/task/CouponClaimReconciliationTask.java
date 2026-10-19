package com.sky.task;

import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CouponClaimReconciliationTask {
    private static final int BATCH_SIZE = 100;

    private final CouponMapper couponMapper;
    private final CouponClaimRedisService claimRedisService;
    private Long lastActivityId = 0L;

    @Scheduled(fixedDelayString = "${sky.coupon.claim-reconcile-delay-ms:30000}",
            initialDelayString = "${sky.coupon.claim-reconcile-initial-delay-ms:30000}")
    public void reconcileActivityInventory() {
        reconcileActivityInventoryPages();
    }

    private void reconcileActivityInventoryPages() {
        List<CouponActivity> activities = couponMapper.listActivitiesAfterId(lastActivityId, BATCH_SIZE);
        if (activities.isEmpty()) {
            lastActivityId = 0L;
            return;
        }
        for (CouponActivity activity : activities) {
            lastActivityId = activity.getId();
            reconcileInventory(activity.getId());
        }
    }

    private void reconcileInventory(Long activityId) {
        String lockToken = null;
        try {
            lockToken = claimRedisService.acquireReconciliationLock(activityId);
            if (lockToken == null) return;
            CouponClaimRedisService.InventorySnapshot redisInventory =
                    claimRedisService.getInventory(activityId);
            if (redisInventory == null || couponMapper.countPendingClaims(activityId) > 0) return;

            reconcileOrphanedReservations(activityId);
            CouponActivity activity = couponMapper.getActivity(activityId);
            if (activity == null) return;
            Map<Long, Integer> databaseCounts = new HashMap<>();
            for (Map<String, Object> row : couponMapper.listSuccessfulClaimCounts(activityId)) {
                Number userId = (Number) row.get("userId");
                Number claimCount = (Number) row.get("claimCount");
                databaseCounts.put(userId.longValue(), claimCount.intValue());
            }
            if (redisInventory.stockRemaining().equals(activity.getStockRemaining()) &&
                    redisInventory.userClaimCounts().equals(databaseCounts)) {
                return;
            }
            if (!claimRedisService.reconcileInventory(activityId, lockToken,
                    activity.getStockRemaining(), databaseCounts)) {
                throw new IllegalStateException("Redis库存对账脚本未完成");
            }
            log.warn("已修复优惠券Redis库存及限领计数，activityId={}, stock {} -> {}",
                    activityId, redisInventory.stockRemaining(), activity.getStockRemaining());
        } catch (RuntimeException failure) {
            log.error("优惠券库存对账失败，activityId={}", activityId, failure);
        } finally {
            if (lockToken != null) {
                try {
                    claimRedisService.releaseReconciliationLock(activityId, lockToken);
                } catch (RuntimeException unlockFailure) {
                    log.error("优惠券库存对账锁释放失败，activityId={}", activityId, unlockFailure);
                }
            }
        }
    }

    private void reconcileOrphanedReservations(Long activityId) {
        for (Map.Entry<String, String> reservation :
                claimRedisService.getReservations(activityId).entrySet()) {
            CouponClaimRecord record = couponMapper.getClaimByBusinessNo(reservation.getKey());
            if (record != null && record.getStatus() == 1) {
                if (!record.getUserId().toString().equals(reservation.getValue())) {
                    throw new IllegalStateException("成功领取流水的用户与Redis预占用户不一致");
                }
                if (!claimRedisService.completeReservation(
                        activityId, record.getUserId(), record.getBusinessNo())) {
                    log.warn("成功领取流水对应的Redis预占标记已不存在，businessNo={}",
                            record.getBusinessNo());
                }
            } else if (record == null || record.getStatus() == 2) {
                Long userId = record == null ? Long.valueOf(reservation.getValue()) : record.getUserId();
                if (!userId.toString().equals(reservation.getValue())) {
                    throw new IllegalStateException("失败领取流水的用户与Redis预占用户不一致");
                }
                claimRedisService.compensateReservation(activityId, userId, reservation.getKey());
            }
        }
    }

}
