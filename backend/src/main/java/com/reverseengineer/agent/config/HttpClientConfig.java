package com.reverseengineer.agent.config;

import okhttp3.OkHttpClient;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;

import java.time.Duration;


@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient longTimeoutOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();
    }

    @Bean
    public RestClientCustomizer longTimeoutRestClientCustomizer(OkHttpClient longTimeoutOkHttpClient) {
        var factory = new OkHttp3ClientHttpRequestFactory(longTimeoutOkHttpClient);
        return builder -> builder.requestFactory(factory);
    }
}
