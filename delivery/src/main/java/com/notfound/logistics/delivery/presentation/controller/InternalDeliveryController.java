package com.notfound.logistics.delivery.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.delivery.application.service.DeliveryCreationService;
import com.notfound.logistics.delivery.presentation.dto.DeliveryCreateRequest;
import com.notfound.logistics.delivery.presentation.dto.DeliveryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 호출 전용 배송 API. Gateway는 /api/** 만 라우팅하므로 외부에 노출되지 않는다.
 *
 * <p>TODO: 서비스 인증(X-Service-Token)과 호출 서비스별 허용 API 검사하기 (orders만 허용)
 */
@RestController
@RequestMapping("/internal/deliveries")
@RequiredArgsConstructor
class InternalDeliveryController {
  private final DeliveryCreationService deliveryCreationService;

  /**
   * 주문에 대한 배송과 허브 간 이동 경로를 생성한다. 성공 시 201과 생성된 배송(경로 포함)을 응답한다. 같은 내용의 재요청은 기존 배송을 201로 그대로 응답하고,
   * 같은 주문에 다른 내용이면 409 DELIVERY_ALREADY_EXISTS. 경로가 없으면 404 HUB_ROUTE_NOT_FOUND, Hub 호출 실패 시 503
   * HUB_UNAVAILABLE.
   */
  @PostMapping
  ResponseEntity<ApiResponse<DeliveryResponse>> create(
      @Valid @RequestBody DeliveryCreateRequest request) {
    DeliveryResponse response =
        DeliveryResponse.from(deliveryCreationService.create(request.toCommand()));
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
  }
}
