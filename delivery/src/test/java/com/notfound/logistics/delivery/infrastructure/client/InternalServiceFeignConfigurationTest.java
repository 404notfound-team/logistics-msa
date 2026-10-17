package com.notfound.logistics.delivery.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.infrastructure.security.ServiceAuthentication;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/** 내부 호출 인증 헤더 규칙: 토큰은 항상, 원 요청자는 인증된 요청에 있을 때만 붙이고, 토큰이 비면 기동하지 않는다. */
class InternalServiceFeignConfigurationTest {
  private final InternalServiceFeignConfiguration configuration =
      new InternalServiceFeignConfiguration();

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void blankTokenFailsStartup() {
    assertThatThrownBy(() -> configuration.internalServiceAuthInterceptor(" "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DELIVERY_SERVICE_TOKEN");
  }

  @Test
  void addsTokenAndRelaysTheActorOfTheAuthenticatedRequest() {
    SecurityContextHolder.getContext()
        .setAuthentication(new ServiceAuthentication("orders", "orders-user"));

    RequestTemplate template = apply("token-value");

    assertThat(template.headers().get("X-Service-Token")).containsExactly("token-value");
    assertThat(template.headers().get("X-Actor-Username")).containsExactly("orders-user");
  }

  @Test
  void omitsTheActorOutsideAnAuthenticatedServiceCall() {
    RequestTemplate template = apply("token-value");

    assertThat(template.headers().get("X-Service-Token")).containsExactly("token-value");
    assertThat(template.headers()).doesNotContainKey("X-Actor-Username");
  }

  @Test
  void omitsTheActorWhenTheCallingServiceSentNone() {
    SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication("orders", null));

    RequestTemplate template = apply("token-value");

    assertThat(template.headers()).doesNotContainKey("X-Actor-Username");
  }

  private RequestTemplate apply(String token) {
    RequestInterceptor interceptor = configuration.internalServiceAuthInterceptor(token);
    RequestTemplate template = new RequestTemplate();
    interceptor.apply(template);
    return template;
  }
}
