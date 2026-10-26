package org.openapitools.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * 只有 WebClient.Builder 会被 Spring Cloud LoadBalancer 后处理；
     * 标注在 WebClient 上不生效。
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
    }

    /**
     * AI 服务客户端：host 使用 Nacos 服务名，由 LoadBalancer 解析成实例地址。
     */
    @Bean
    public WebClient webClient(@LoadBalanced WebClient.Builder builder,
                               @Value("${ai.service.name:gitlet-ai-helper}") String aiServiceName) {
        return builder.baseUrl("http://" + aiServiceName).build();
    }
}
