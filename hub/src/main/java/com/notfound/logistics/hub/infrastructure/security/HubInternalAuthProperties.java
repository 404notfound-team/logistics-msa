package com.notfound.logistics.hub.infrastructure.security;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hub.internal-auth")
public record HubInternalAuthProperties(Map<String, Client> clients) {
  public HubInternalAuthProperties {
    clients = clients == null ? Map.of() : Map.copyOf(clients);
  }

  public record Client(String token, List<String> allowed) {
    public Client {
      token = token == null ? "" : token;
      allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }
  }
}
