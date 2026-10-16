package com.notfound.logistics.users.infrastructure.security;

import java.util.List;
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

/** 인증된 호출 서비스에 설정에서 허용한 내부 API인지 확인합니다. */
public class InternalApiAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {
  private final Map<String, List<AllowedApi>> allowedByService;

  public InternalApiAuthorizationManager(InternalAuthProperties properties) {
    this.allowedByService =
        properties.clients().entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry ->
                        entry.getValue().allowed().stream()
                            .map(rule -> AllowedApi.parse(entry.getKey(), rule))
                            .toList()));
  }

  @Override
  public AuthorizationDecision check(
      Supplier<Authentication> authentication, RequestAuthorizationContext context) {
    if (!(authentication.get() instanceof ServiceAuthentication service)) {
      return new AuthorizationDecision(false);
    }
    var request = context.getRequest();
    HttpMethod method = HttpMethod.valueOf(request.getMethod());
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    boolean allowed =
        allowedByService.getOrDefault(service.getServiceName(), List.of()).stream()
            .anyMatch(api -> api.matches(method, path));
    return new AuthorizationDecision(allowed);
  }

  private record AllowedApi(HttpMethod method, PathPattern pattern) {
    static AllowedApi parse(String serviceName, String rule) {
      String[] parts = rule.strip().split("\\s+");
      if (parts.length != 2) {
        throw new IllegalStateException(
            "users.internal-auth.clients."
                + serviceName
                + ".allowed must use 'METHOD /path-pattern': "
                + rule);
      }
      return new AllowedApi(
          HttpMethod.valueOf(parts[0]), PathPatternParser.defaultInstance.parse(parts[1]));
    }

    boolean matches(HttpMethod requestMethod, PathContainer path) {
      return method.equals(requestMethod) && pattern.matches(path);
    }
  }
}
