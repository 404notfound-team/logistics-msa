package com.notfound.logistics.users.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Service-Token이 등록된 서비스의 인증값과 일치하면 {@link ServiceAuthentication}을 구성합니다. 일치하지 않으면 인증 정보를 비워 두어
 * 인가 단계에서 401로 거절됩니다.
 */
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {
  public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
  public static final String ACTOR_USERNAME_HEADER = "X-Actor-Username";

  private final Map<String, byte[]> tokensByService;

  public ServiceTokenAuthenticationFilter(InternalAuthProperties properties) {
    this.tokensByService =
        properties.clients().entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> tokenBytes(entry.getKey(), entry.getValue())));
    long distinctTokens =
        tokensByService.values().stream()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .distinct()
            .count();
    if (distinctTokens != tokensByService.size()) {
      throw new IllegalStateException("Each internal service must use a distinct service token");
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String token = request.getHeader(SERVICE_TOKEN_HEADER);
    if (token != null && !token.isBlank()) {
      String serviceName = findService(token.getBytes(StandardCharsets.UTF_8));
      if (serviceName != null) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ServiceAuthentication(serviceName, actorUsername(request)));
        SecurityContextHolder.setContext(context);
      }
    }
    filterChain.doFilter(request, response);
  }

  private String findService(byte[] presented) {
    String matched = null;
    for (var entry : tokensByService.entrySet()) {
      // Compare against every client in constant time so timing does not reveal which one matched.
      if (MessageDigest.isEqual(entry.getValue(), presented)) {
        matched = entry.getKey();
      }
    }
    return matched;
  }

  private String actorUsername(HttpServletRequest request) {
    String actor = request.getHeader(ACTOR_USERNAME_HEADER);
    return actor == null || actor.isBlank() ? null : actor.strip();
  }

  private static byte[] tokenBytes(String serviceName, InternalAuthProperties.Client client) {
    if (client == null || client.token() == null || client.token().isBlank()) {
      throw new IllegalStateException(
          "users.internal-auth.clients." + serviceName + ".token must not be blank");
    }
    return client.token().getBytes(StandardCharsets.UTF_8);
  }
}
