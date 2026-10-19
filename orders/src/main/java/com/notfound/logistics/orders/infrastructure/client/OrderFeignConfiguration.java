package com.notfound.logistics.orders.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.infrastructure.audit.OrdersAuditorAware;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

// Selected only by business clients, never by the local infrastructure probe.
public class OrderFeignConfiguration {
  @Bean
  Retryer orderRetryer() {
    return Retryer.NEVER_RETRY;
  }

  @Bean
  RequestInterceptor orderRequestHeaders(
      OrdersAuditorAware auditor, @Value("${orders.clients.service-token:}") String serviceToken) {
    return template -> {
      template.header("X-Service-Token", serviceToken);
      template.header("X-Actor-Username", auditor.getCurrentAuditor().orElseThrow());
    };
  }

  @Bean
  ErrorDecoder orderErrorDecoder(ObjectMapper mapper) {
    return (method, response) -> {
      String code = "UPSTREAM_UNAVAILABLE";
      if (response.body() != null) {
        try (var input = response.body().asInputStream()) {
          String candidate = mapper.readTree(input.readNBytes(65536)).path("code").asText();
          if (candidate.matches("[A-Z_]{1,80}")) code = candidate;
        } catch (Exception ignored) {
          // An unparseable response cannot establish a definite business failure.
        }
      }
      return new RemoteFailure(response.status(), code);
    };
  }
}
