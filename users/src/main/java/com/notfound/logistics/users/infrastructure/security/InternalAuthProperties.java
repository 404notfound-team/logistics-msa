package com.notfound.logistics.users.infrastructure.security;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Users 내부 API를 호출할 수 있는 서비스 목록입니다.
 *
 * @param clients 호출 서비스 이름별 인증값과 허용 API(`METHOD /path-pattern`)
 */
@ConfigurationProperties("users.internal-auth")
public record InternalAuthProperties(Map<String, Client> clients) {
  public InternalAuthProperties {
    clients = clients == null ? Map.of() : Map.copyOf(clients);
  }

  public record Client(String token, List<String> allowed) {
    public Client {
      allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }
  }
}
