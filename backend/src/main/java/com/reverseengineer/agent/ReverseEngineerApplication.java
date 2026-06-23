package com.reverseengineer.agent;

import com.reverseengineer.agent.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class ReverseEngineerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReverseEngineerApplication.class, args);
    }
}
