package com.notfound.logistics.delivery.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Hub 서비스 내부 API 클라이언트. Eureka에 등록된 이름(hub)으로 호출하고, 인증 헤더는 InternalServiceFeignConfiguration이 붙인다.
 * Hub는 Delivery에 GET /internal/hub-routes만 허용한다.
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

  /** Hub 응답의 기준 경로. 응답은 snake_case이며 전역 Jackson 설정으로 매핑된다. 거리는 km, 소요 시간은 분 단위다. */
  record HubRoute(
      UUID id,
      UUID departureHubId,
      UUID arrivalHubId,
      Integer durationMinutes,
      BigDecimal distanceKm) {}
}
