package com.notfound.logistics.hub.application.dto.hub;

import com.notfound.logistics.hub.domain.model.hub.Hub;
import java.util.UUID;

public record HubResult(UUID id, String name, String address) {

  public static HubResult from(Hub hub) {
    return new HubResult(hub.getId(), hub.getName(), hub.getAddress());
  }
}
