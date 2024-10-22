package com.crane.apiplatformgateway;

import cn.hutool.core.util.StrUtil;
import com.crane.apiplatformsdk.client.ApiClient;
import com.crane.apiplatformsdk.constant.ErrorStatus;
import com.crane.apiplatformsdk.exception.BusinessException;
import com.crane.apiplatformsdk.model.dto.SignDto;
import com.crane.constant.RedisKey;
import com.crane.constant.SignHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 全局过滤器
 *
 * @Date 2024/10/19 21:34
 * @Author Crane Resigned
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private final List<String> whiteList = List.of("127.0.0.1");

    private final StringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //白名单过滤
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        String remoteAddress = Objects.requireNonNull(request.getRemoteAddress()).getHostString();
        if (!whiteList.contains(remoteAddress)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        //签名校验
        HttpHeaders headers = request.getHeaders();
        String sign = headers.getFirst(SignHeader.SIGN);
        String accessKey = headers.getFirst(SignHeader.ACCESS_KEY);
        String timestamp = headers.getFirst(SignHeader.TIMESTAMP);
        String body = headers.getFirst(SignHeader.BODY);
        String nonce = headers.getFirst(SignHeader.NONCE);
        if (StrUtil.hasBlank(sign, accessKey, timestamp, body, nonce)) {
            throw new BusinessException(ErrorStatus.NULL_ERROR, "签名参数为空");
        }
        assert timestamp != null;
        assert nonce != null;
        //校验timestamp
        long currentTime = System.currentTimeMillis();
        if ((currentTime - Long.parseLong(timestamp)) / 1000 > 60) {
            log.error("请求超时");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        //校验nonce
        ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();
        Boolean b = zSet.addIfAbsent(RedisKey.NONCE_Z_SET_KEY, nonce, Double.parseDouble(timestamp));
        if (!Boolean.TRUE.equals(b)) {
            log.error("请求重复");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        //删除0~90秒前的key
        zSet.removeRange(RedisKey.NONCE_Z_SET_KEY, 0, currentTime - 90000);

        //校验签名
        ApiClient apiClient = new ApiClient();
        SignDto signDto = new SignDto();
        signDto.setAccessKey(apiClient.getAccessKey());
        signDto.setNonce(nonce);
        signDto.setData(body);
        signDto.setTimestamp(Long.valueOf(timestamp));
        if (!StrUtil.equals(apiClient.getSign(signDto), sign)) {
            log.error("签名验证失败");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        //TODO:验证请求的接口是否存在，以及请求参数是否正确？

        Mono<Void> filter = chain.filter(exchange);
        //记录日志
        //调用次数增减
        if (response.getStatusCode() == HttpStatus.OK) {
            //TODO:调用减次数逻辑
            return handleDecoratorResponse(exchange, chain);
        } else {
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * https://blog.csdn.net/qq_19636353/article/details/126759522
     *
     * @author CraneResigned
     * @date 2024/10/22 19:37
     **/
    public Mono<Void> handleDecoratorResponse(ServerWebExchange exchange, GatewayFilterChain chain) {
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            HttpStatus statusCode = (HttpStatus) originalResponse.getStatusCode();
            if (statusCode != HttpStatus.OK) {
                return chain.filter(exchange);//降级处理返回数据
            }
            ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    if (body instanceof Flux) {
                        Flux<? extends DataBuffer> fluxBody = Flux.from(body);

                        return super.writeWith(fluxBody.buffer().map(dataBuffers -> {

                            // 合并多个流集合，解决返回体分段传输
                            DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
                            DataBuffer buff = dataBufferFactory.join(dataBuffers);
                            byte[] content = new byte[buff.readableByteCount()];
                            buff.read(content);
                            DataBufferUtils.release(buff);//释放掉内存

                            // 构建返回日志
                            String joinData = new String(content);
                            List<Object> rspArgs = new ArrayList<>();
                            rspArgs.add(originalResponse.getStatusCode().value());
                            rspArgs.add(exchange.getRequest().getURI());
                            rspArgs.add(joinData);
                            log.info("<-- {} {}\n{}", rspArgs.toArray());

                            getDelegate().getHeaders().setContentLength(joinData.getBytes().length);
                            return bufferFactory.wrap(joinData.getBytes());
                        }));
                    } else {
                        log.error("<-- {} 响应code异常", getStatusCode());
                    }
                    return super.writeWith(body);
                }
            };
            return chain.filter(exchange.mutate().response(decoratedResponse).build());

        } catch (Exception e) {
            log.error("gateway log exception.\n" + e);
            return chain.filter(exchange);
        }
    }

}
