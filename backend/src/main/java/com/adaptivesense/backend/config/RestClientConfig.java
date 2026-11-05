package com.adaptivesense.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${ollama.connect-timeout:5s}")
    private Duration ollamaConnectTimeout;

    @Value("${ollama.read-timeout:180s}")
    private Duration ollamaReadTimeout;

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ollamaConnectTimeout);
        requestFactory.setReadTimeout(ollamaReadTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}
