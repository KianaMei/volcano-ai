package com.volcano.chat.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// 暂时排除数据源自动配置，因为目前不启用 MySQL 记录功能
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }

}
