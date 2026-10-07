package com.myagent.team;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "myagent.team")
public class TeamProperties {
    private long reconcileIntervalMs = 2000;
    private volatile int leaseSeconds = 600;
    private volatile int deliveryMaxAttempts = 3;
    private volatile int wakeBatchSize = 50;
    /** 失败重投指数退避基数(秒):第 n 次重试等待 base*2^(n-2)+抖动,首试不等待 */
    private volatile int retryBackoffBaseSeconds = 5;
    /** 失败重投退避封顶(秒) */
    private volatile int retryBackoffCapSeconds = 120;
}
