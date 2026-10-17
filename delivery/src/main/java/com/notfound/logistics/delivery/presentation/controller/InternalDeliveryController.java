package com.notfound.logistics.delivery.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.delivery.presentation.dto.DeliveryCreateRequest;
import com.notfound.logistics.delivery.presentation.dto.DeliveryResponse;
import jakarta.validation.Valid;
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
class InternalDeliveryController {

  /**
   * 주문에 대한 배송과 허브 간 이동 경로를 생성한다. 성공 시 201과 생성된 배송을 응답한다.
   *
   * <p>TODO: 배송 생성 서비스 호출하기 (Hub에서 경로 조회 → 배송·경로 저장)
   */
  @PostMapping
  ResponseEntity<ApiResponse<DeliveryResponse>> create(
      @Valid @RequestBody DeliveryCreateRequest request) {
    throw new UnsupportedOperationException("배송 생성은 아직 구현되지 않았습니다");
  }
}
