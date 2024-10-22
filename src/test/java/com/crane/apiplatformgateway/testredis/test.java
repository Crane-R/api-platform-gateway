package com.crane.apiplatformgateway.testredis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * @Date 2024/10/22 16:57
 * @Author Crane Resigned
 */
@SpringBootTest
public class test {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void testStringRedisTemplate() {
        redisTemplate.opsForValue().set("name", "虎哥");
        Object name = redisTemplate.opsForValue().get("name");
        System.out.println("name:" + name);
    }

    /**
     * 测试setnx语法
     *
     * @author CraneResigned
     * @date 2024/10/22 17:45
     **/
    @Test
    void testExpire() {

        ZSetOperations<String, String> zSetOperations = redisTemplate.opsForZSet();
        zSetOperations.addIfAbsent("api:zsettest", "uuid1", System.currentTimeMillis());
        zSetOperations.addIfAbsent("api:zsettest", "uuid2", System.currentTimeMillis());
        zSetOperations.addIfAbsent("api:zsettest", "uuid3", System.currentTimeMillis());
        zSetOperations.addIfAbsent("api:zsettest", "uuid4", System.currentTimeMillis());
//        zSetOperations.removeRange()
        Boolean b = zSetOperations.addIfAbsent("api:abc:zsettest", "uuid5", System.currentTimeMillis());
        System.out.println(b);

    }

}
