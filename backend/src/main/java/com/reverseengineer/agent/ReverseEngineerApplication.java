package com.reverseengineer.agent;

import com.reverseengineer.agent.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class ReverseEngineerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReverseEngineerApplication.class, args);
    }
}
