package com.crane.apiplatformgateway;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.crane.apiplatformcommon.constant.ErrorStatus;
import com.crane.apiplatformcommon.exception.BusinessException;
import com.crane.apiplatformcommon.model.dto.SignDto;
import com.crane.apiplatformcommon.model.vo.InterfaceInfoVo;
import com.crane.apiplatformcommon.model.vo.UserVo;
import com.crane.apiplatformcommon.service.InterfaceInfoService;
import com.crane.apiplatformcommon.service.UserInterfaceInfoService;
import com.crane.apiplatformcommon.service.UserService;
import com.crane.constant.RedisKey;
import com.crane.constant.SignHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
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
import java.util.HashMap;
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
//@CrossOrigin(origins = "http://localhost:8000", allowCredentials = "true")
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private final List<String> whiteList = List.of("127.0.0.1", "0:0:0:0:0:0:0:1");

    /**
     * 只检测接口调用
     *
     * @author CraneResigned
     * @date 2024/10/25 10:37
     **/
    private final List<String> checkList = List.of(
            "/api/interface/invoke"
    );

    private final StringRedisTemplate redisTemplate;

    @DubboReference
    private UserService userService;

    @DubboReference
    private UserInterfaceInfoService userInterfaceInfoService;

    @DubboReference
    private InterfaceInfoService interfaceInfoService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        //只检测执行接口方法，其他请求全部放行
        if (!checkList.contains(request.getPath().value())) {
            return chain.filter(exchange);
        }

        //白名单过滤
        ServerHttpResponse response = exchange.getResponse();
        String remoteAddress = Objects.requireNonNull(request.getRemoteAddress()).getHostString();
        if (!whiteList.contains(remoteAddress)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        //签名校验
        //todo:前端不知道怎么传header过来，暂时校验不了
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
        SignDto signDto = new SignDto();
        signDto.setAccessKey(accessKey);
        signDto.setNonce(nonce);
        signDto.setData(body);
        signDto.setTimestamp(Long.valueOf(timestamp));
        if (!StrUtil.equals(userService.getSign(signDto), sign)) {
            log.error("签名验证失败");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }

        //获取用户
        UserVo userVo = null;
        try {
            userVo = userService.getUserByAccessKey(accessKey);
        } catch (Exception e) {
            throw new BusinessException(ErrorStatus.NULL_ERROR, "网关用户为空");
        }
        //获取接口信息
        InterfaceInfoVo interfaceInfoVo = null;
        HashMap<String, Object> bodyMap = JSONUtil.toBean(body, HashMap.class);
        try {
            interfaceInfoVo = interfaceInfoService.interfaceSelectOne(Long.parseLong(bodyMap.get("interfaceId").toString()));
        } catch (Exception e) {
            throw new BusinessException(ErrorStatus.NULL_ERROR, "接口信息为空");
        }
        Mono<Void> filter = chain.filter(exchange);
        //记录日志
        //调用次数增减
        if (response.getStatusCode() == HttpStatus.OK) {
            return handleDecoratorResponse(exchange, chain, userVo.getId(), interfaceInfoVo.getId());
        }
        return filter;
    }

    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * <a href="https://blog.csdn.net/qq_19636353/article/details/126759522">...</a>
     * todo:这里不理解的话就先这样把，但还是建议弄懂啊
     *
     * @author CraneResigned
     * @date 2024/10/22 19:37
     **/
    public Mono<Void> handleDecoratorResponse(ServerWebExchange exchange, GatewayFilterChain chain, long userId, long interfaceId) {
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

                            //TODO:调用减次数逻辑，在这里调用 ，待测试
                            userInterfaceInfoService.userInterfaceInvokeNumChange(userId, interfaceId);

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
