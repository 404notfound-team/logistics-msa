package com.notfound.logistics.hub.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.infrastructure.audit.HubAuditorAware;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

// Company 상품 조회에만 적용하며 전역 Feign 설정으로 등록하지 않는다.
public class CompanyFeignConfiguration {
  @Bean
  RequestInterceptor companyRequestHeaders(
      HubAuditorAware auditor, @Value("${hub.internal-auth.service-token:}") String token) {
    if (token.isBlank()) throw new IllegalStateException("HUB_SERVICE_TOKEN must not be blank");
    return template -> {
      template.header("X-Service-Token", token);
      template.header("X-Actor-Username", auditor.getCurrentAuditor().orElseThrow());
    };
  }

  @Bean
  Retryer companyRetryer() {
    return Retryer.NEVER_RETRY;
  }

  @Bean
  Request.Options companyRequestOptions(
      @Value("${hub.clients.connect-timeout-ms:2000}") int connect,
      @Value("${hub.clients.read-timeout-ms:5000}") int read) {
    if (connect <= 0 || read <= 0)
      throw new IllegalStateException("Client timeouts must be positive");
    return new Request.Options(connect, TimeUnit.MILLISECONDS, read, TimeUnit.MILLISECONDS, false);
  }

  @Bean
  ErrorDecoder companyErrorDecoder(ObjectMapper mapper) {
    return (method, response) -> {
      if (response.status() == 404 && response.body() != null) {
        try (var input = response.body().asInputStream()) {
          var body = mapper.readTree(input.readNBytes(65536));
          if (body != null && "PRODUCT_NOT_FOUND".equals(body.path("code").asText())) {
            return new StockBusinessException(StockErrorCode.PRODUCT_NOT_FOUND);
          }
        } catch (Exception ignored) {
          // 프록시의 404 또는 해석할 수 없는 오류로 상품 부재를 확정하지 않는다.
        }
      }
      return new ProductLookupException();
    };
  }
}
