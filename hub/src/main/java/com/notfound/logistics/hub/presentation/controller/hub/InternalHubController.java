package com.notfound.logistics.hub.presentation.controller.hub;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import com.notfound.logistics.hub.presentation.dto.hub.HubNameResponse;
import com.notfound.logistics.hub.presentation.dto.hub.HubResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/hubs")
public class InternalHubController {

  private final HubQueryService hubQueryService;

  // 허브명 완전 일치 검색
  @GetMapping
  public ApiResponse<List<HubNameResponse>> getHubsByName(
      @RequestParam("name") @NotBlank String name) {

    return new ApiResponse<>(
        hubQueryService.getHubsByName(name).stream().map(HubNameResponse::from).toList());
  }

  // 허브 ID로 단건 조회
  @GetMapping("/{id}")
  public ApiResponse<HubResponse> getHub(@PathVariable UUID id) {

    return new ApiResponse<>(HubResponse.from(hubQueryService.getHub(id)));
  }
}
