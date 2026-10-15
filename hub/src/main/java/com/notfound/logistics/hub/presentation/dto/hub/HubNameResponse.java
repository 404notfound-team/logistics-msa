package com.notfound.logistics.hub.presentation.dto.hub;

import com.notfound.logistics.hub.application.dto.hub.HubResult;
import java.util.UUID;

public record HubNameResponse(
  UUID id,
  String name) {

  public static HubNameResponse from(HubResult result) {
    return new HubNameResponse(
      result.id(),
      result.name());
  }
}
