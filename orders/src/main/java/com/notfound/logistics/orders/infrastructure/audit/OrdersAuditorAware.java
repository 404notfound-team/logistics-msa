package com.notfound.logistics.orders.infrastructure.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The authentication layer must establish a verified username before a write transaction. */
@Component("ordersAuditorAware")
public class OrdersAuditorAware implements AuditorAware<String> {
  /** Run recovery as the persisted original requester, without granting user roles. */
  public void runAsPersistedUser(String username, Runnable work) {
    if (username == null || username.isBlank() || username.length() > 100)
      throw new IllegalStateException("Missing persisted recovery username");
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      work.run();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  @Override
  public Optional<String> getCurrentAuditor() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      throw new IllegalStateException("A verified audit actor is required");
    }
    var username = authentication.getName();
    if (username == null || username.isBlank() || username.length() > 100) {
      throw new IllegalStateException("A valid audit username is required");
    }
    return Optional.of(username);
  }
}
