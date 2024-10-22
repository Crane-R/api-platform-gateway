package com.crane.apiplatformgateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest  
class SpringdataredisDemoApplicationTests {  
  
    @Autowired  
    private StringRedisTemplate stringRedisTemplate;
  
    @Test  
    void contextLoads() {

        stringRedisTemplate.opsForValue().set("name", "虎哥");  
        Object name = stringRedisTemplate.opsForValue().get("name");
        System.out.println("name:" + name);  
    }  
  
}