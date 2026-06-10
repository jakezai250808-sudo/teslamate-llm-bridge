package io.teslabridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 不扩展 ComponentScan 到 io.teslamate.play：play-engine-core 的 @Component 类
// 由 TeslamateDataSource @Configuration 显式 @Bean 注册，保持 core 注入框架无关。
@SpringBootApplication
public class BridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}
