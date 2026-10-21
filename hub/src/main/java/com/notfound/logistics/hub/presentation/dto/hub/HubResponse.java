package com.notfound.logistics.hub.presentation.dto.hub;

import com.notfound.logistics.hub.application.dto.hub.HubResult;
import java.util.UUID;

public record HubResponse(UUID id, String name, String address) {

  public static HubResponse from(HubResult result) {
    return new HubResponse(result.id(), result.name(), result.address());
  }
}
