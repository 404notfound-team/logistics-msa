package com.notfound.logistics.hub.presentation.controller.hubroute;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.presentation.dto.hubroute.HubRouteResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/hub-routes")
public class InternalHubRouteController {

  private final HubRouteQueryService hubRouteQueryService;

  @GetMapping
  public ApiResponse<HubRouteResponse> getRoute(
      @RequestParam("departure_hub_id") UUID departureHubId,
      @RequestParam("arrival_hub_id") UUID arrivalHubId) {

    return new ApiResponse<>(
        HubRouteResponse.from(hubRouteQueryService.getRoute(departureHubId, arrivalHubId)));
  }
}
