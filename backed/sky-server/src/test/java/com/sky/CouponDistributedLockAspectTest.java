package com.sky;

import com.sky.annotation.CouponDistributedLock;
import com.sky.aspect.CouponDistributedLockAspect;
import com.sky.dto.CouponActivityDTO;
import com.sky.exception.CouponBusinessException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponDistributedLockAspectTest {
    @Test
    void resolvesSpelKeyAndReleasesLockAfterTargetReturns() throws InterruptedException {
        LockTarget.calls.set(0);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ObjectProvider<RedissonClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(redissonClient);
        when(redissonClient.getLock("sky:lock:coupon-test:42")).thenReturn(lock);
        when(lock.tryLock(3L, 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        LockTarget target = proxy(clientProvider);
        CouponActivityDTO dto = new CouponActivityDTO();
        dto.setId(42L);

        assertEquals("updated", target.update(dto));

        verify(lock).unlock();
        assertEquals(1, LockTarget.calls.get());
    }

    @Test
    void lockTimeoutRejectsCallWithoutProceedingToTarget() throws InterruptedException {
        LockTarget.calls.set(0);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ObjectProvider<RedissonClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(redissonClient);
        when(redissonClient.getLock("sky:lock:coupon-test:42")).thenReturn(lock);
        when(lock.tryLock(3L, 60L, TimeUnit.SECONDS)).thenReturn(false);

        LockTarget target = proxy(clientProvider);
        CouponActivityDTO dto = new CouponActivityDTO();
        dto.setId(42L);

        assertThrows(CouponBusinessException.class, () -> target.update(dto));
        assertEquals(0, LockTarget.calls.get());
    }

    @Test
    void releasesLockWhenTargetThrows() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ObjectProvider<RedissonClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(redissonClient);
        when(redissonClient.getLock("sky:lock:coupon-test:42")).thenReturn(lock);
        when(lock.tryLock(3L, 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        LockTarget target = proxy(clientProvider);
        CouponActivityDTO dto = new CouponActivityDTO();
        dto.setId(42L);

        assertThrows(IllegalStateException.class, () -> target.fail(dto));

        verify(lock).unlock();
    }

    private LockTarget proxy(ObjectProvider<RedissonClient> provider) {
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new LockTarget());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(new CouponDistributedLockAspect(provider));
        return proxyFactory.getProxy();
    }

    public static class LockTarget {
        private static final AtomicInteger calls = new AtomicInteger();

        @CouponDistributedLock(name = "coupon-test", key = "#p0.id")
        public String update(CouponActivityDTO dto) {
            calls.incrementAndGet();
            return "updated";
        }

        @CouponDistributedLock(name = "coupon-test", key = "#p0.id")
        public void fail(CouponActivityDTO dto) {
            throw new IllegalStateException("target failed");
        }
    }
}
