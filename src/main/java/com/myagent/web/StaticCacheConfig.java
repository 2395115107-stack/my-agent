package com.myagent.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 静态资源缓存策略:no-cache(强制 revalidate)。
 * HTML/JS/CSS 均带 ?v= 版本号,内容变化即拉新、未变则 304;
 * 没有 no-cache 时浏览器启发式缓存会让前端迭代后仍拿到旧文件。
 */
@Configuration
public class StaticCacheConfig {

    @Bean
    public WebFilter staticNoCacheFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (path.equals("/") || path.endsWith(".html")
                    || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/fonts/")) {
                exchange.getResponse().getHeaders().setCacheControl("no-cache");
            }
            return chain.filter(exchange);
        };
    }
}
