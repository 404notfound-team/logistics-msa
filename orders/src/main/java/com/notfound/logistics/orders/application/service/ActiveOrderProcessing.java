package com.notfound.logistics.orders.application.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Single orders instance, as specified by the local Compose design. */
@Component
public class ActiveOrderProcessing {
  public enum Operation {
    CREATE,
    CANCEL,
    DELETE,
    RECOVER
  }

  private final ConcurrentHashMap<UUID, Operation> active = new ConcurrentHashMap<>();

  /** Returns the operation that already owns this order, or null when acquired. */
  public Operation begin(UUID id, Operation operation) {
    return active.putIfAbsent(id, operation);
  }

  /** Runs while the expected owner cannot be released or replaced for this order. */
  public boolean whileRunning(UUID id, Operation expected, Runnable action) {
    var executed = new AtomicBoolean();
    active.computeIfPresent(
        id,
        (key, running) -> {
          if (running == expected) {
            action.run();
            executed.set(true);
          }
          return running;
        });
    return executed.get();
  }

  public void end(UUID id) {
    active.remove(id);
  }
}
