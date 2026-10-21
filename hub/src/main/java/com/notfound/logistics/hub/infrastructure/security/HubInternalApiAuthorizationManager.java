package com.notfound.logistics.hub.infrastructure.security;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Users 기준 구현과 같이 METHOD /path-pattern 설정으로 호출 권한을 검사한다. */
final class HubInternalApiAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {
  private final Map<String, List<AllowedApi>> allowed;

  HubInternalApiAuthorizationManager(HubInternalAuthProperties properties) {
    allowed =
        properties.clients().entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    entry -> "SERVICE_" + entry.getKey().toUpperCase(Locale.ROOT),
                    entry -> entry.getValue().allowed().stream().map(AllowedApi::parse).toList()));
  }

  @Override
  public AuthorizationDecision check(
      Supplier<Authentication> authentication, RequestAuthorizationContext context) {
    Authentication caller = authentication.get();
    if (caller == null || !caller.isAuthenticated()) return new AuthorizationDecision(false);
    var request = context.getRequest();
    var path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    boolean permitted =
        caller.getAuthorities().stream()
            .flatMap(
                authority -> allowed.getOrDefault(authority.getAuthority(), List.of()).stream())
            .anyMatch(
                rule -> rule.method().matches(request.getMethod()) && rule.path().matches(path));
    return new AuthorizationDecision(permitted);
  }

  private record AllowedApi(HttpMethod method, PathPattern path) {
    static AllowedApi parse(String rule) {
      String[] parts = rule.strip().split("\\s+");
      if (parts.length != 2 || !parts[1].startsWith("/internal/"))
        throw new IllegalStateException("Allowed API must use 'METHOD /internal/path-pattern'");
      return new AllowedApi(
          HttpMethod.valueOf(parts[0]), PathPatternParser.defaultInstance.parse(parts[1]));
    }
  }
}
