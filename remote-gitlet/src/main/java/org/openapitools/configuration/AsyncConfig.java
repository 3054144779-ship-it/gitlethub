package org.openapitools.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 * 针对 4G 内存小机器与外部 API 限流优化
 */
@Configuration
@EnableConfigurationProperties(PoolsConfig.class)
public class AsyncConfig {

    /**
     * 推送相关的异步线程池配置
     * @return
     */
    @Bean(name = "taskExecutorForPush")
    public Executor taskExecutorForPush(PoolsConfig p) {
        return build("push", p.getPush(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 拉取相关的异步线程池配置
     * @return
     */
    @Bean(name = "taskExecutorForPull")
    public Executor taskExecutorForPull(PoolsConfig p) {
        return build("pull", p.getPull(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 默认的异步线程池配置
     * @return
     */
    // 兜底：以后新写的无参 @Async 不会退化成线程/任务
    @Bean(name = "taskExecutor")
    public Executor taskExecutor(PoolsConfig p) {
        return build("default", p.getEvent(), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * AI 相关的异步线程池配置
     * @return
     */
    @Bean(name = "taskExecutorForAi")
    public Executor taskExecutorForAi(PoolsConfig p) {
        return build("ai", p.getAi(), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadPoolTaskExecutor build(String name, PoolsConfig.Pool pool, RejectedExecutionHandler executionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueSize());
        executor.setThreadNamePrefix("Async-" + name + "-");
        executor.setRejectedExecutionHandler(executionHandler);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }


}