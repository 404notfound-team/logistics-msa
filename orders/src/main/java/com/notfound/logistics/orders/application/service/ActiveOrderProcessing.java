package com.notfound.logistics.orders.application.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Single orders instance, as specified by the local Compose design. */
@Component
public class ActiveOrderProcessing {
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();

  public boolean begin(UUID id) {
    return active.add(id);
  }

  public void end(UUID id) {
    active.remove(id);
  }
}
