package com.notfound.logistics.orders.infrastructure.security;

import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class OrderActors {
  public record Actor(String username, boolean master) {}

  public Actor require() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken
        || authentication.getName() == null
        || authentication.getName().isBlank()
        || authentication.getName().length() > 100) {
      throw new OrderBusinessException(OrderErrorCode.AUTHENTICATION_REQUIRED);
    }
    return new Actor(
        authentication.getName(),
        authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_MASTER")));
  }

  /** Only a persisted, originally authenticated actor may be supplied by recovery. */
  public <T> T asPersistedActor(String username, Supplier<T> work) {
    if (username == null || username.isBlank() || username.length() > 100)
      throw new IllegalStateException("Missing persisted recovery actor");
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
