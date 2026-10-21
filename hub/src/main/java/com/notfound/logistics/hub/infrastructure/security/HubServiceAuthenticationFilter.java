package com.notfound.logistics.hub.infrastructure.security;

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

// SecurityFilterChain에만 등록한다. 서블릿 필터로 중복 등록하지 않는다.
final class HubServiceAuthenticationFilter extends OncePerRequestFilter {
  private final Map<String, String> tokens;
  private final AuthenticationEntryPoint unauthorized;

  HubServiceAuthenticationFilter(
      Map<String, String> tokens, AuthenticationEntryPoint unauthorized) {
    var configured =
        tokens.entrySet().stream().filter(entry -> !entry.getValue().isBlank()).toList();
    var unique = new HashSet<String>();
    for (var entry : configured) {
      if (entry.getValue().length() < 16 || !unique.add(entry.getValue())) {
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
      String supplied = singleHeader(request, "X-Service-Token", true);
      String service = null;
      byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
      for (var entry : tokens.entrySet()) {
        if (!entry.getValue().isBlank()
            && MessageDigest.isEqual(
                entry.getValue().getBytes(StandardCharsets.UTF_8), suppliedBytes)) {
          service = entry.getKey();
        }
      }
      if (service == null) throw new BadCredentialsException("Invalid service credentials");
      boolean signupLookup =
          "USERS".equals(service)
              && "GET".equals(request.getMethod())
              && "/internal/hubs".equals(request.getRequestURI());
      String actor = singleHeader(request, "X-Actor-Username", !signupLookup);
      if (actor != null && (actor.isBlank() || actor.length() > 100)) {
        throw new BadCredentialsException("Invalid audit actor");
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              actor == null ? "" : actor,
              null,
              List.of(new SimpleGrantedAuthority("SERVICE_" + service))));
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

  private String singleHeader(HttpServletRequest request, String name, boolean required) {
    var values = Collections.list(request.getHeaders(name));
    if (values.isEmpty() && !required) return null;
    if (values.size() != 1 || values.getFirst().isBlank()) {
      throw new BadCredentialsException("Missing or ambiguous service credentials");
    }
    return values.getFirst();
  }
}
