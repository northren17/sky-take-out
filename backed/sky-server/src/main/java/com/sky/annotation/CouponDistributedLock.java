package com.sky.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CouponDistributedLock {
    String name();
    String key();
    long waitTime() default 3;
    long leaseTime() default 60;
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
