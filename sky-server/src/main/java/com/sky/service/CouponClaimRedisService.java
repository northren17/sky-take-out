package com.sky.service;

import com.sky.constant.RedisKeyConstant;
import com.sky.entity.CouponActivity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CouponClaimRedisService {
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> activitySyncScript;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> inventoryReconcileScript;
    private final DefaultRedisScript<Long> reconcileUnlockScript;

    public CouponClaimRedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.activitySyncScript = loadScript("lua/coupon_activity_sync.lua");
        this.reserveScript = loadScript("lua/coupon_claim_reserve.lua");
        this.releaseScript = loadScript("lua/coupon_claim_release.lua");
        this.inventoryReconcileScript = loadScript("lua/coupon_claim_inventory_reconcile.lua");
        this.reconcileUnlockScript = loadScript("lua/coupon_claim_reconcile_unlock.lua");
    }

    public void prepareActivity(CouponActivity activity, int pendingClaims) {
        String key = activityKey(activity.getId());
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key)) && pendingClaims > 0) {
            throw new IllegalStateException("存在未完成领取任务，Redis库存需先对账后初始化");
        }
        syncActivity(activity, false);
    }

    public void initializeUserClaimCount(Long activityId, Long userId, int successfulClaims) {
        String key = usersKey(activityId);
        String field = userId.toString();
        if (Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(key, field))) return;
        Boolean initialized = redisTemplate.opsForHash().putIfAbsent(key, field,
                Integer.toString(successfulClaims));
        if (initialized == null) {
            throw new IllegalStateException("初始化用户优惠券领取计数失败");
        }
    }

    public long reserve(CouponActivity activity, Long userId, String businessNo, LocalDateTime now) {
        Long result = redisTemplate.execute(reserveScript,
                List.of(activityKey(activity.getId()), usersKey(activity.getId()),
                        requestsKey(activity.getId()), reconcileLockKey(activity.getId())),
                Long.toString(toEpochMillis(now)),
                userId.toString(),
                businessNo);
        if (result == null) throw new IllegalStateException("Redis优惠券领取脚本未返回结果");
        return result;
    }

    public String getReservation(Long activityId, String businessNo) {
        Object userId = redisTemplate.opsForHash().get(requestsKey(activityId), businessNo);
        return userId == null ? null : userId.toString();
    }

    public Map<String, String> getReservations(Long activityId) {
        Map<String, String> reservations = new HashMap<>();
        redisTemplate.opsForHash().entries(requestsKey(activityId)).forEach((businessNo, userId) ->
                reservations.put(businessNo.toString(), userId.toString()));
        return reservations;
    }

    public InventorySnapshot getInventory(Long activityId) {
        String key = activityKey(activityId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) return null;
        Object stockValue = redisTemplate.opsForHash().get(key, "stockRemaining");
        if (stockValue == null) throw new IllegalStateException("Redis活动库存字段缺失，activityId=" + activityId);
        Map<Long, Integer> userCounts = new HashMap<>();
        for (Map.Entry<Object, Object> entry :
                redisTemplate.opsForHash().entries(usersKey(activityId)).entrySet()) {
            userCounts.put(Long.valueOf(entry.getKey().toString()),
                    Integer.valueOf(entry.getValue().toString()));
        }
        return new InventorySnapshot(Integer.valueOf(stockValue.toString()), userCounts);
    }

    public String acquireReconciliationLock(Long activityId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                reconcileLockKey(activityId), token, 60, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public boolean reconcileInventory(Long activityId, String token, Integer stockRemaining,
                                      Map<Long, Integer> userClaimCounts) {
        List<String> arguments = new ArrayList<>();
        arguments.add(token);
        arguments.add(stockRemaining.toString());
        userClaimCounts.forEach((userId, count) -> {
            arguments.add(userId.toString());
            arguments.add(count.toString());
        });
        Long result = redisTemplate.execute(inventoryReconcileScript,
                List.of(activityKey(activityId), usersKey(activityId), reconcileLockKey(activityId)),
                arguments.toArray());
        return result != null && result == 1;
    }

    public void releaseReconciliationLock(Long activityId, String token) {
        Long result = redisTemplate.execute(reconcileUnlockScript,
                List.of(reconcileLockKey(activityId)), token);
        if (result == null) throw new IllegalStateException("释放优惠券库存对账锁失败");
    }

    public boolean completeReservation(Long activityId, Long userId, String businessNo) {
        Long result = release(activityId, userId, businessNo, "complete");
        if (result == null) throw new IllegalStateException("清理优惠券领取标记失败");
        if (result < 0) {
            throw new IllegalStateException("Redis领取标记与数据库流水不匹配，businessNo=" + businessNo);
        }
        return result == 1;
    }

    public boolean compensateReservation(Long activityId, Long userId, String businessNo) {
        Long result = release(activityId, userId, businessNo, "compensate");
        if (result == null) throw new IllegalStateException("优惠券库存补偿脚本未返回结果");
        if (result < 0) {
            throw new IllegalStateException("优惠券库存补偿状态不一致，activityId=" + activityId +
                    ", businessNo=" + businessNo + ", result=" + result);
        }
        return result > 0;
    }

    public void syncActivity(CouponActivity activity, boolean resetStock) {
        Long result = redisTemplate.execute(activitySyncScript,
                List.of(activityKey(activity.getId()), usersKey(activity.getId()),
                        requestsKey(activity.getId()), reconcileLockKey(activity.getId())),
                activity.getStatus().toString(),
                activity.getStockRemaining().toString(),
                activity.getStockTotal().toString(),
                Long.toString(toEpochMillis(activity.getStartTime())),
                Long.toString(toEpochMillis(activity.getEndTime())),
                activity.getPerUserLimit().toString(),
                resetStock ? "1" : "0");
        if (result == null || result < 0) {
            throw new IllegalStateException("同步优惠券活动状态到Redis失败，activityId=" + activity.getId());
        }
    }

    private Long release(Long activityId, Long userId, String businessNo, String mode) {
        return redisTemplate.execute(releaseScript,
                List.of(activityKey(activityId), usersKey(activityId), requestsKey(activityId)),
                userId.toString(),
                businessNo,
                mode);
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String activityKey(Long activityId) {
        return RedisKeyConstant.COUPON_ACTIVITY + activityId;
    }

    private String usersKey(Long activityId) {
        return RedisKeyConstant.COUPON_ACTIVITY_USERS + activityId;
    }

    private String requestsKey(Long activityId) {
        return RedisKeyConstant.COUPON_ACTIVITY_REQUESTS + activityId;
    }

    private String reconcileLockKey(Long activityId) {
        return RedisKeyConstant.COUPON_ACTIVITY_RECONCILE_LOCK + activityId;
    }

    private DefaultRedisScript<Long> loadScript(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    public record InventorySnapshot(Integer stockRemaining, Map<Long, Integer> userClaimCounts) {
    }
}
