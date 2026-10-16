package com.notfound.logistics.gateway.auth;

import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 외부 클라이언트가 넣은 서비스 인증·사용자 식별 헤더를 하위 서비스로 전달하지 않습니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UntrustedHeaderRemovalFilter implements GlobalFilter {
  private static final Set<String> UNTRUSTED_HEADERS =
      Set.of("x-service-token", "x-actor-username");
  private static final String UNTRUSTED_HEADER_PREFIX = "x-user-";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    var request =
        exchange
            .getRequest()
            .mutate()
            .headers(headers -> headers.keySet().removeIf(this::isUntrusted))
            .build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  private boolean isUntrusted(String headerName) {
    String name = headerName.toLowerCase(Locale.ROOT);
    return UNTRUSTED_HEADERS.contains(name) || name.startsWith(UNTRUSTED_HEADER_PREFIX);
  }
}
