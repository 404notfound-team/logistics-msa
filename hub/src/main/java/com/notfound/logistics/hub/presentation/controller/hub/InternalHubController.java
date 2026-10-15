package com.notfound.logistics.hub.presentation.controller.hub;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import com.notfound.logistics.hub.presentation.dto.hub.HubResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/hubs")
public class InternalHubController {

  private final HubQueryService hubQueryService;

  @GetMapping("/{id}")
  public ApiResponse<HubResponse> getHub(@PathVariable UUID id) {

    return new ApiResponse<>(HubResponse.from(hubQueryService.getHub(id)));
  }
}
