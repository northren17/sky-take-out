package com.sky;

import com.sky.constant.RedisKeyConstant;
import com.sky.entity.CouponTemplate;
import com.sky.service.CouponRuleCache;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponRuleCacheTest {
    @Test
    void reusesLocalRuleUntilSharedVersionChanges() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(RedisKeyConstant.COUPON_RULE_CACHE_VERSION)).thenReturn("0", "0", "1");
        when(values.get(RedisKeyConstant.COUPON_RULE_CACHE_TEMPLATE + "5:v0")).thenReturn(null);
        when(values.get(RedisKeyConstant.COUPON_RULE_CACHE_TEMPLATE + "5:v1")).thenReturn(null);
        when(values.increment(RedisKeyConstant.COUPON_RULE_CACHE_VERSION)).thenReturn(1L);

        CouponRuleCache cache = new CouponRuleCache(redisTemplate);
        AtomicInteger databaseReads = new AtomicInteger();

        CouponTemplate first = cache.getTemplate(5L, () -> template(databaseReads.incrementAndGet()));
        CouponTemplate cached = cache.getTemplate(5L, () -> template(databaseReads.incrementAndGet()));
        cache.invalidateTemplate(5L);
        CouponTemplate refreshed = cache.getTemplate(5L, () -> template(databaseReads.incrementAndGet()));

        assertEquals("Template 1", first.getName());
        assertEquals("Template 1", cached.getName());
        assertEquals("Template 2", refreshed.getName());
        assertEquals(2, databaseReads.get());
    }

    private CouponTemplate template(int read) {
        CouponTemplate template = new CouponTemplate();
        template.setName("Template " + read);
        return template;
    }
}
