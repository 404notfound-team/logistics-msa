package com.notfound.logistics.delivery.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Service-Token이 등록된 서비스의 인증값과 일치하면 {@link ServiceAuthentication}을 구성한다. 일치하지 않으면 인증 정보를 비워 두어 인가
 * 단계에서 401로 거절된다. SecurityFilterChain에만 등록하고 서블릿 필터로 중복 등록하지 않는다.
 */
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {
  public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
  public static final String ACTOR_USERNAME_HEADER = "X-Actor-Username";

  private final Map<String, byte[]> tokensByService;

  public ServiceTokenAuthenticationFilter(InternalAuthProperties properties) {
    // 토큰이 비어 있는 서비스는 등록하지 않는다. 그 서비스의 호출은 모두 401이 된다.
    this.tokensByService =
        properties.clients().entrySet().stream()
            .filter(entry -> !entry.getValue().token().isBlank())
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().token().getBytes(StandardCharsets.UTF_8)));
    Set<String> distinct = new HashSet<>();
    for (byte[] token : tokensByService.values()) {
      if (!distinct.add(new String(token, StandardCharsets.UTF_8))) {
        throw new IllegalStateException("Each internal service must use a distinct service token");
      }
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
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ServiceAuthentication(serviceName, actorUsername(request)));
        SecurityContextHolder.setContext(context);
      }
    }
    filterChain.doFilter(request, response);
  }

  private String findService(byte[] presented) {
    String matched = null;
    for (Map.Entry<String, byte[]> entry : tokensByService.entrySet()) {
      // 모든 서비스와 고정 시간으로 비교해 응답 시간으로 어느 토큰과 일치했는지 드러나지 않게 한다.
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
}
