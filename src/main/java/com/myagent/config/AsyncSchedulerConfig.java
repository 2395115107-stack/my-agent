package com.myagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 调度与异步:TeamScheduler 的对账循环、记忆抽取等异步任务共用。
 */
@Configuration
@EnableScheduling
@EnableAsync
public class AsyncSchedulerConfig {

    @Bean("teamWorker")
    public Executor teamWorker() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("team-worker-");
        executor.initialize();
        return executor;
    }
}
