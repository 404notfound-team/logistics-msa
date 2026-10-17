package com.notfound.logistics.delivery.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Hub 서비스 내부 API 클라이언트. Eureka에 등록된 이름(hub)으로 호출하고, 인증 헤더는 InternalServiceFeignConfiguration이 붙인다.
 * Delivery가 쓰는 API(GET /internal/hubs/{id}, GET /internal/hub-routes)는 Hub의
 * hub.internal-auth.clients.delivery.allowed에 등록되어 있어야 한다. 없으면 403.
 */
@FeignClient(
    name = "hub",
    contextId = "deliveryHub",
    configuration = InternalServiceFeignConfiguration.class)
public interface HubClient {

  /** 출발 허브 → 도착 허브의 기준 이동 경로를 조회한다. 경로가 없으면 Hub가 404(HUB_ROUTE_NOT_FOUND)를 응답한다. */
  @GetMapping("/internal/hub-routes")
  ApiResponse<HubRoute> getRoute(
      @RequestParam("departure_hub_id") UUID departureHubId,
      @RequestParam("arrival_hub_id") UUID arrivalHubId);

  /** 허브 ID로 허브 정보를 조회한다. 없거나 삭제된 허브면 Hub가 404(HUB_NOT_FOUND)를 응답한다. */
  @GetMapping("/internal/hubs/{id}")
  ApiResponse<Hub> getHub(@PathVariable("id") UUID id);

  /** Hub 응답의 허브 정보. */
  record Hub(UUID id, String name, String address) {}

  /** Hub 응답의 기준 경로. 응답은 snake_case이며 전역 Jackson 설정으로 매핑된다. 거리는 km, 소요 시간은 분 단위다. */
  record HubRoute(
      UUID id,
      UUID departureHubId,
      UUID arrivalHubId,
      Integer durationMinutes,
      BigDecimal distanceKm) {}
}
