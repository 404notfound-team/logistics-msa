package com.notfound.logistics.delivery.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.delivery.application.dto.DeliveryCreationResult;
import com.notfound.logistics.delivery.application.service.DeliveryCreationService;
import com.notfound.logistics.delivery.presentation.dto.DeliveryCreateRequest;
import com.notfound.logistics.delivery.presentation.dto.DeliveryCreateResponse;
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
   * 주문에 대한 배송과 허브 간 이동 경로를 생성한다. 응답 본문은 delivery_id, order_id, status다.
   *
   * <ul>
   *   <li>201: 새로 생성
   *   <li>200: 같은 주문·같은 내용의 재요청이라 기존 배송을 반환
   *   <li>400: 입력 오류
   *   <li>404: 허브(HUB_NOT_FOUND) 또는 기준 경로(HUB_ROUTE_NOT_FOUND) 없음
   *   <li>409: 같은 주문에 다른 내용의 배송이 이미 있음(DELIVERY_ALREADY_EXISTS)
   * </ul>
   */
  @PostMapping
  ResponseEntity<ApiResponse<DeliveryCreateResponse>> create(
      @Valid @RequestBody DeliveryCreateRequest request) {
    DeliveryCreationResult result = deliveryCreationService.create(request.toCommand());
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(new ApiResponse<>(DeliveryCreateResponse.from(result.delivery())));
  }
}
