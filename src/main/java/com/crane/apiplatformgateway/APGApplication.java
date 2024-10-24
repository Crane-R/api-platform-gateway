package com.crane.apiplatformgateway;

import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * @author Crane Resigned
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
@EnableDubbo
@DubboService
public class APGApplication {

    public static void main(String[] args) {
        SpringApplication.run(APGApplication.class, args);
    }

//    @Bean
//    public RouteLocator myRoutes(RouteLocatorBuilder builder) {
//        return builder.routes()
//                // Add a simple re-route from: /get to: http://httpbin.org:80
//                // Add a simple "Hello:World" HTTP Header
//                .route(p -> p
//                        .path("/get") // intercept calls to the /get path
//                        .filters(f -> f.addRequestHeader("Hello", "World")) // add header
//                        .uri("http://httpbin.org:80")) // forward to httpbin
//                .build();
//    }


}
