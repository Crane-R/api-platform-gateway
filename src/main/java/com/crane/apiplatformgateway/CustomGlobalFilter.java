package com.crane.apiplatformgateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局过滤器
 *
 * @Date 2024/10/19 21:34
 * @Author Crane Resigned
 */
@Slf4j
@Component
public class GlobalFilter implements org.springframework.cloud.gateway.filter.GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {


        Mono<Void> filter = chain.filter(exchange);
        //记录日志
        //调用次数增减

        return filter;
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
