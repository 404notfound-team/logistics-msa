package com.notfound.logistics.orders.global.config;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The authentication layer must establish a verified username before a write transaction. */
// TODO(#27): Connect verified Gateway actor data to SecurityContext
// after agreeing the authentication contract with #21.
@Component("ordersAuditorAware")
public class OrdersAuditorAware implements AuditorAware<String> {
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
