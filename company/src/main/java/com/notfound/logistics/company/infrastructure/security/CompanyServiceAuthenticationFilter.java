package com.notfound.logistics.company.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 내부 API(/internal/**)의 서비스 인증. X-Service-Token으로 호출 서비스를 식별해 SERVICE_{이름} 권한을 주고,
 * X-Actor-Username은 서비스 인증이 끝난 뒤에만 감사 주체로 쓴다(hub의 HubServiceAuthenticationFilter와 같은 규격).
 * SecurityFilterChain에만 등록한다. 서블릿 필터로 중복 등록하지 않는다.
 */
final class CompanyServiceAuthenticationFilter extends OncePerRequestFilter {
  private final Map<String, String> tokens;
  private final AuthenticationEntryPoint unauthorized;

  CompanyServiceAuthenticationFilter(
      Map<String, String> tokens, AuthenticationEntryPoint unauthorized) {
    var unique = new HashSet<String>();
    for (var token : tokens.values()) {
      if (token.isBlank()) {
        continue;
      }
      if (token.length() < 16 || !unique.add(token)) {
        throw new IllegalStateException(
            "Internal service tokens must be distinct and at least 16 characters");
      }
    }
    this.tokens = Map.copyOf(tokens);
    this.unauthorized = unauthorized;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      String supplied = singleHeader(request, "X-Service-Token");
      // 비어 있는 토큰은 설정되지 않은 서비스이므로 어떤 값과도 일치시키지 않는다.
      String service =
          tokens.entrySet().stream()
              .filter(entry -> !entry.getValue().isBlank())
              .filter(
                  entry ->
                      MessageDigest.isEqual(
                          entry.getValue().getBytes(StandardCharsets.UTF_8),
                          supplied.getBytes(StandardCharsets.UTF_8)))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow(() -> new BadCredentialsException("Invalid service credentials"));
      String actor = singleHeader(request, "X-Actor-Username");
      if (actor.length() > 100) {
        throw new BadCredentialsException("Invalid audit actor");
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              actor, null, List.of(new SimpleGrantedAuthority("SERVICE_" + service))));
      SecurityContextHolder.setContext(context);
    } catch (BadCredentialsException exception) {
      SecurityContextHolder.clearContext();
      unauthorized.commence(request, response, exception);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  // 같은 헤더가 여러 번 오면 어느 값을 믿을지 정할 수 없으므로 거부한다.
  private static String singleHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() != 1 || values.getFirst().isBlank()) {
      throw new BadCredentialsException("Missing or ambiguous service credentials");
    }
    return values.getFirst();
  }
}
