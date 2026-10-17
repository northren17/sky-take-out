package com.sky.aspect;

import com.sky.annotation.CouponDistributedLock;
import com.sky.exception.CouponBusinessException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CouponDistributedLockAspect {
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer =
            new DefaultParameterNameDiscoverer();

    @Around("@annotation(com.sky.annotation.CouponDistributedLock)")
    public Object executeWithLock(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(),
                joinPoint.getTarget().getClass());
        CouponDistributedLock lock = AnnotatedElementUtils.findMergedAnnotation(
                method, CouponDistributedLock.class);
        if (lock == null) throw new IllegalStateException("锁切面未读取到方法上的锁配置");
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                null, method, joinPoint.getArgs(), parameterNameDiscoverer);
        Object keyValue = expressionParser.parseExpression(lock.key()).getValue(context);
        if (keyValue == null || !StringUtils.hasText(keyValue.toString())) {
            throw new CouponBusinessException("分布式锁业务键不能为空");
        }

        String lockName = "sky:lock:" + lock.name() + ":" + keyValue;
        RLock distributedLock = redissonClientProvider.getObject().getLock(lockName);
        boolean acquired;
        try {
            acquired = distributedLock.tryLock(lock.waitTime(), lock.leaseTime(), lock.timeUnit());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CouponBusinessException("获取优惠券业务锁时被中断");
        }
        if (!acquired) throw new CouponBusinessException("当前业务正在处理中，请稍后重试");

        try {
            return joinPoint.proceed();
        } finally {
            if (distributedLock.isHeldByCurrentThread()) {
                distributedLock.unlock();
            }
        }
    }
}
