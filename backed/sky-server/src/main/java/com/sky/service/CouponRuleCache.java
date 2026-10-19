package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sky.constant.RedisKeyConstant;
import com.sky.entity.CouponTemplate;
import com.sky.vo.CouponActivityRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Service
@Slf4j
public class CouponRuleCache {
    private static final String NULL_VALUE = "__coupon_rule_not_found__";
    private static final Duration LOCAL_TTL = Duration.ofSeconds(30);
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(LOCAL_TTL)
            .build();

    public CouponRuleCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public CouponTemplate getTemplate(Long id, Supplier<CouponTemplate> loader) {
        return load(RedisKeyConstant.COUPON_RULE_CACHE_TEMPLATE + id, CouponTemplate.class, loader);
    }

    public CouponActivityRule getActivityRule(Long id, Supplier<CouponActivityRule> loader) {
        return load(RedisKeyConstant.COUPON_RULE_CACHE_ACTIVITY + id,
                CouponActivityRule.class, loader);
    }

    public void invalidateTemplate(Long id) {
        invalidate(RedisKeyConstant.COUPON_RULE_CACHE_TEMPLATE + id);
    }

    public void invalidateActivity(Long id) {
        invalidate(RedisKeyConstant.COUPON_RULE_CACHE_ACTIVITY + id);
    }

    private <T> T load(String baseKey, Class<T> type, Supplier<T> loader) {
        long version;
        try {
            version = currentVersion();
        } catch (RuntimeException cacheFailure) {
            log.error("读取优惠券规则缓存版本失败，改为查询数据库，key={}", baseKey, cacheFailure);
            return loader.get();
        }

        String versionedKey = baseKey + ":v" + version;
        String cached = localCache.getIfPresent(versionedKey);
        if (cached == null) {
            try {
                cached = redisTemplate.opsForValue().get(versionedKey);
            } catch (RuntimeException cacheFailure) {
                log.error("读取Redis优惠券规则缓存失败，改为查询数据库，key={}", versionedKey, cacheFailure);
                return loader.get();
            }
        }
        if (cached != null) {
            if (NULL_VALUE.equals(cached)) return null;
            localCache.put(versionedKey, cached);
            return JSON.parseObject(cached, type);
        }

        T value = loader.get();
        String encoded = value == null ? NULL_VALUE : JSON.toJSONString(value);
        localCache.put(versionedKey, encoded);
        try {
            redisTemplate.opsForValue().set(versionedKey, encoded, REDIS_TTL);
        } catch (RuntimeException cacheFailure) {
            log.error("写入Redis优惠券规则缓存失败，key={}", versionedKey, cacheFailure);
        }
        return value;
    }

    private long currentVersion() {
        String version = redisTemplate.opsForValue().get(RedisKeyConstant.COUPON_RULE_CACHE_VERSION);
        if (version == null) {
            redisTemplate.opsForValue().setIfAbsent(
                    RedisKeyConstant.COUPON_RULE_CACHE_VERSION, "0");
            version = redisTemplate.opsForValue().get(RedisKeyConstant.COUPON_RULE_CACHE_VERSION);
        }
        if (version == null) throw new IllegalStateException("优惠券规则缓存版本未初始化");
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException invalidVersion) {
            throw new IllegalStateException("优惠券规则缓存版本无效", invalidVersion);
        }
    }

    private void invalidate(String key) {
        localCache.invalidateAll();
        Long version = redisTemplate.opsForValue()
                .increment(RedisKeyConstant.COUPON_RULE_CACHE_VERSION);
        if (version == null) throw new IllegalStateException("更新优惠券规则缓存版本失败，key=" + key);
    }
}
