package com.notfound.logistics.hub.domain.hub.presentation.dto;

import com.notfound.logistics.hub.domain.hub.application.dto.HubResult;
import java.util.UUID;

public record HubResponse(UUID id, String name, String address) {

  public static HubResponse from(HubResult result) {
    return new HubResponse(result.id(), result.name(), result.address());
  }
}
