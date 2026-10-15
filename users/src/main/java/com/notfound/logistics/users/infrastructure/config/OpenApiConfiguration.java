package com.notfound.logistics.users.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfiguration {
  @Bean
  ModelResolver openApiModelResolver(
      ObjectMapper applicationMapper, ObjectMapperProvider provider) {
    // Keep Swagger's schema support while matching the application's JSON field names.
    var schemaMapper =
        provider
            .jsonMapper()
            .copy()
            .setPropertyNamingStrategy(applicationMapper.getPropertyNamingStrategy());
    return new ModelResolver(schemaMapper).openapi31(provider.isOpenapi31());
  }
}
