package com.myagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 自研智能体平台:Phoenix 引擎范式(ReactAgent/Hook/Interceptor/Redis 检查点)
 * × AionUi 团队分配机制(任务板/信箱/调度器,Java+PG 重实现)。
 */
@SpringBootApplication
@MapperScan("com.myagent.team.mapper")
public class MyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
