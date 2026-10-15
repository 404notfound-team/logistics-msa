package com.notfound.logistics.orders.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OrderAuthenticationFilter implements Filter {
  private final JwtDecoder decoder;
  private final ObjectMapper mapper;

  public OrderAuthenticationFilter(
      @Value("${orders.jwt.secret:}") String secret, ObjectMapper mapper) {
    this.mapper = mapper;
    if (secret.isBlank()) {
      decoder = null;
      return;
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalStateException("JWT_SECRET requires at least 32 UTF-8 bytes");
    var configured =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    configured.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator("logistics-users"),
            new JwtClaimValidator<Instant>("exp", Objects::nonNull),
            new JwtClaimValidator<String>(
                "sub", value -> value != null && !value.isBlank() && value.length() <= 100),
            new JwtClaimValidator<String>(
                "role", value -> Set.of("USER", "MASTER").contains(value == null ? "" : value))));
    decoder = configured;
  }

  @Override
  public void doFilter(ServletRequest incoming, ServletResponse outgoing, FilterChain chain)
      throws IOException, ServletException {
    var request = (HttpServletRequest) incoming;
    var response = (HttpServletResponse) outgoing;
    String path = request.getRequestURI();
    if (!path.equals("/api/orders") && !path.startsWith("/api/orders/")) {
      chain.doFilter(incoming, outgoing);
      return;
    }
    String authorization = request.getHeader("Authorization");
    try {
      if (decoder == null || authorization == null || !authorization.startsWith("Bearer ")) {
        unauthorized(response);
        return;
      }
      var jwt = decoder.decode(authorization.substring(7));
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              jwt.getSubject(),
              authorization.substring(7),
              List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role")))));
      SecurityContextHolder.setContext(context);
    } catch (JwtException | IllegalArgumentException exception) {
      unauthorized(response);
      return;
    }
    try {
      chain.doFilter(incoming, outgoing);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(401);
    response.setContentType("application/json");
    mapper.writeValue(
        response.getOutputStream(),
        ApiErrorResponse.of(CommonErrorCode.UNAUTHORIZED, MDC.get("traceId")));
  }
}
