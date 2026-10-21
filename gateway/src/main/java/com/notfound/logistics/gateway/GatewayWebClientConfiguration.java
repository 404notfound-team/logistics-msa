package com.notfound.logistics.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GatewayWebClientConfiguration {
  /**
   * 직접 등록한 Builder는 Boot 기본 Builder를 대신하므로 Boot의 customizer를 직접 적용합니다. 빠지면 Users 호출에 trace 헤더가 붙지
   * 않아 원 요청과 다른 trace로 기록됩니다.
   */
  @Bean
  @LoadBalanced
  WebClient.Builder loadBalancedWebClientBuilder(ObjectProvider<WebClientCustomizer> customizers) {
    WebClient.Builder builder = WebClient.builder();
    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
    return builder;
  }
}
