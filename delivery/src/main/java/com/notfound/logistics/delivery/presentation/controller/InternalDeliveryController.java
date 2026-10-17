package com.notfound.logistics.delivery.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.delivery.application.dto.DeliveryCreationResult;
import com.notfound.logistics.delivery.application.service.DeliveryCancellationService;
import com.notfound.logistics.delivery.application.service.DeliveryCreationService;
import com.notfound.logistics.delivery.application.service.DeliveryDeletionService;
import com.notfound.logistics.delivery.application.service.DeliveryQueryService;
import com.notfound.logistics.delivery.presentation.dto.DeliveryCreateRequest;
import com.notfound.logistics.delivery.presentation.dto.DeliveryCreateResponse;
import com.notfound.logistics.delivery.presentation.dto.OrderDeliveryResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 호출 전용 배송 API. Gateway는 /api/** 만 라우팅하므로 외부에 노출되지 않는다.
 *
 * <p>X-Service-Token 인증과 호출 서비스별 허용 API 검사는 DeliverySecurityConfiguration이 맡는다(현재 orders의 배송 생성·주문별
 * 조회·취소·삭제만 허용).
 */
@RestController
@RequestMapping("/internal/deliveries")
@RequiredArgsConstructor
class InternalDeliveryController {
  private final DeliveryCreationService deliveryCreationService;
  private final DeliveryQueryService deliveryQueryService;
  private final DeliveryCancellationService deliveryCancellationService;
  private final DeliveryDeletionService deliveryDeletionService;

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

  /**
   * 주문의 배송을 조회한다. 응답 본문은 delivery_id, order_id, status다.
   *
   * <ul>
   *   <li>200: 주문의 배송
   *   <li>400: order_id가 UUID가 아님
   *   <li>404: 아직 조회되는 배송 없음(DELIVERY_NOT_FOUND)
   * </ul>
   *
   * <p>호출 측 계약: 배송 생성 요청이 타임아웃된 직후에는 생성이 아직 끝나지 않았을 수 있으므로, 이 404만으로 배송 생성 실패를 확정하지 않는다. 다시 조회하거나
   * 같은 내용으로 생성을 재요청(멱등)해 결과를 확인한다.
   */
  @GetMapping("/by-order/{order_id}")
  ApiResponse<OrderDeliveryResponse> findByOrder(@PathVariable("order_id") UUID orderId) {
    return new ApiResponse<>(
        OrderDeliveryResponse.from(deliveryQueryService.searchByOrderId(orderId)));
  }

  /**
   * 배송과 배송의 모든 경로를 취소한다. 응답 본문은 delivery_id, order_id, status다.
   *
   * <ul>
   *   <li>200: 취소된 배송
   *   <li>400: id가 UUID가 아님
   *   <li>404: 배송 없음(DELIVERY_NOT_FOUND)
   *   <li>409: 이미 출발해 취소할 수 없음(DELIVERY_ALREADY_STARTED)
   * </ul>
   */
  @PostMapping("/{id}/cancel")
  ApiResponse<OrderDeliveryResponse> cancel(@PathVariable("id") UUID id) {
    return new ApiResponse<>(OrderDeliveryResponse.from(deliveryCancellationService.cancel(id)));
  }

  /**
   * 배송과 배송의 모든 경로를 논리 삭제한다. 요청 본문은 없다.
   *
   * <ul>
   *   <li>204: 삭제됨. 이미 삭제된 배송의 재요청도 204
   *   <li>400: id가 UUID가 아님
   *   <li>404: 배송 없음(DELIVERY_NOT_FOUND)
   *   <li>409: 배송이 끝나지 않음(DELIVERY_IN_PROGRESS). DELIVERED·CANCELLED만 삭제할 수 있다.
   * </ul>
   *
   * <p>호출 측 계약: 배송이 생성되지 않은 주문은 삭제를 호출하지 않는다.
   */
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
    deliveryDeletionService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
