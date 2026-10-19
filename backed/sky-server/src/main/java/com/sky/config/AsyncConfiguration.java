package com.sky.config;

import com.sky.context.BaseContext;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步任务（@Async）与虚拟线程配置
 * <p>
 * Spring Boot 3.2 起提供 {@code spring.threads.virtual.enabled=true}，
 * 该开关的作用是：为 Tomcat 的请求处理线程池、以及自动配置的 applicationTaskExecutor
 * 使用虚拟线程（JDK21 的 Thread.ofVirtual()）。
 * <p>
 * 本类额外做两件事：
 * <ol>
 *     <li>显式声明一个基于虚拟线程的 {@link AsyncTaskExecutor}，@Async 方法直接跑在虚拟线程上；</li>
 *     <li>注册 {@link TaskDecorator}，把调用方线程的 BaseContext（ThreadLocal 保存的登录用户 id）
 *         传递给虚拟线程，避免异步任务里 BaseContext.getCurrentId() 取不到值。</li>
 * </ol>
 * 说明：Java 21 的虚拟线程在遇到 synchronized 块时会发生“固定”（pinning，JEP 491 已在 JDK24 解决），
 * 因此高并发场景下仍建议用 ReentrantLock 替代 synchronized；本项目业务代码未使用自定义 synchronized 块。
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

    /**
     * 虚拟线程执行器：每个任务一个虚拟线程（thread-per-task），
     * 无需线程池排队，适合 IO 密集型的 Web / MQ 场景。
     * <p>
     * 使用 Spring Boot 的 {@link SimpleAsyncTaskExecutorBuilder} 构建，
     * 可以直接复用 application.yml 中 {@code spring.task.execution.simple.*} 的并发上限与优雅停机配置。
     */
    @Bean
    public AsyncTaskExecutor taskExecutor(SimpleAsyncTaskExecutorBuilder builder) {
        return builder
                .virtualThreads(true)
                // 传递 BaseContext，保证 @Async 方法内仍能拿到当前登录用户
                .taskDecorator(new BaseContextTaskDecorator())
                .build();
    }

    /**
     * 把提交任务线程的 BaseContext 复制到执行任务的虚拟线程中，并在任务结束后清理，避免 ThreadLocal 泄漏。
     */
    static class BaseContextTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            Long currentId = BaseContext.getCurrentId();
            return () -> {
                try {
                    if (currentId != null) {
                        BaseContext.setCurrentId(currentId);
                    }
                    runnable.run();
                } finally {
                    BaseContext.removeCurrentId();
                }
            };
        }
    }
}
