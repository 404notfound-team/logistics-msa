package com.notfound.logistics.orders.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class OrderRuntimeConfiguration {
  public OrderRuntimeConfiguration(@Value("${orders.clients.service-token:}") String serviceToken) {
    if (serviceToken.isBlank() || serviceToken.length() < 16) {
      throw new IllegalStateException("ORDERS_SERVICE_TOKEN requires at least 16 characters");
    }
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer orderIntegerValidation() {
    return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }
}
