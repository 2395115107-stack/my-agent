package com.myagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 ObjectMapper:Spring Boot 4 默认装配的是 Jackson 3(tools.jackson),
 * 骨架内部(信箱 payload / 任务依赖 JSON)统一用 Jackson 2 API,这里显式声明。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
