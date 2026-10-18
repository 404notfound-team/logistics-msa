package com.notfound.logistics.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webflux.ui.SwaggerIndexPageTransformer;
import org.springdoc.webflux.ui.SwaggerIndexTransformer;
import org.springdoc.webflux.ui.SwaggerWelcomeCommon;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.resource.ResourceTransformerChain;
import org.springframework.web.reactive.resource.TransformedResource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 기존 Swagger UI에 실제 Users 로그인과 페이지 내 인증 공유를 추가한다. */
@Profile("local")
@Configuration(proxyBeanMethods = false)
class LocalSwaggerLoginConfiguration {
  @Bean
  SwaggerIndexTransformer swaggerIndexTransformer(
      SwaggerUiConfigProperties config,
      SwaggerUiOAuthProperties oauth,
      SwaggerWelcomeCommon welcome,
      ObjectMapperProvider mapper)
      throws IOException {
    String loginScript =
        new ClassPathResource("swagger/login.js").getContentAsString(StandardCharsets.UTF_8);
    return new SwaggerIndexPageTransformer(config, oauth, welcome, mapper) {
      @Override
      public Mono<Resource> transform(
          ServerWebExchange exchange, Resource resource, ResourceTransformerChain chain) {
        return super.transform(exchange, resource, chain)
            .map(
                transformed -> {
                  if (!"swagger-initializer.js".equals(transformed.getFilename())) {
                    return transformed;
                  }
                  try (var input = transformed.getInputStream()) {
                    String initializer = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    String marker = "SwaggerUIBundle({";
                    if (!initializer.contains(marker)) {
                      throw new IllegalStateException("Swagger UI initializer format changed");
                    }
                    String content =
                        loginScript
                            + "\n"
                            + initializer
                                .replace(
                                    marker,
                                    marker
                                        + "\nrequestInterceptor: logisticsSwaggerLogin.request,"
                                        + "\nonComplete: logisticsSwaggerLogin.complete,")
                                .replace(
                                    "SwaggerUIBundle.plugins.DownloadUrl",
                                    "SwaggerUIBundle.plugins.DownloadUrl, logisticsSwaggerLogin.plugin");
                    return new TransformedResource(
                        transformed, content.getBytes(StandardCharsets.UTF_8));
                  } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                  }
                });
      }
    };
  }
}
