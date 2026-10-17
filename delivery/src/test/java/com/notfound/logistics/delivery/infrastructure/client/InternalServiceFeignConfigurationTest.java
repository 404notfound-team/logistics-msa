package com.notfound.logistics.delivery.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 내부 호출 인증 헤더 규칙: 토큰은 항상, 원 요청자는 받은 요청에 있을 때만 붙이고, 토큰이 비면 기동하지 않는다. */
class InternalServiceFeignConfigurationTest {
  private final InternalServiceFeignConfiguration configuration =
      new InternalServiceFeignConfiguration();

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void blankTokenFailsStartup() {
    assertThatThrownBy(() -> configuration.internalServiceAuthInterceptor(" "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DELIVERY_SERVICE_TOKEN");
  }

  @Test
  void addsTokenAndRelaysTheActorOfTheCurrentRequest() {
    MockHttpServletRequest incoming = new MockHttpServletRequest();
    incoming.addHeader("X-Actor-Username", "orders-user");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));

    RequestTemplate template = apply("token-value");

    assertThat(template.headers().get("X-Service-Token")).containsExactly("token-value");
    assertThat(template.headers().get("X-Actor-Username")).containsExactly("orders-user");
  }

  @Test
  void omitsTheActorOutsideAnHttpRequest() {
    RequestTemplate template = apply("token-value");

    assertThat(template.headers().get("X-Service-Token")).containsExactly("token-value");
    assertThat(template.headers()).doesNotContainKey("X-Actor-Username");
  }

  private RequestTemplate apply(String token) {
    RequestInterceptor interceptor = configuration.internalServiceAuthInterceptor(token);
    RequestTemplate template = new RequestTemplate();
    interceptor.apply(template);
    return template;
  }
}
