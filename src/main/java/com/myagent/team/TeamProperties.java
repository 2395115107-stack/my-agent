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
}
