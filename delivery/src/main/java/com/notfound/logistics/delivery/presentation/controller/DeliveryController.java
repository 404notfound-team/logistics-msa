package com.notfound.logistics.delivery.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.application.service.DeliveryQueryService;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import com.notfound.logistics.delivery.presentation.dto.DeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "배송", description = "허브 간 이동과 업체 배송")
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
class DeliveryController {
  private final DeliveryQueryService deliveryQueryService;

  @Operation(summary = "배송 목록 조회·검색", description = "지정한 조건을 모두 만족하는 배송을 페이지로 조회합니다.")
  @GetMapping
  ApiResponse<PageResponse<DeliveryResponse>> search(
      @Parameter(description = "주문 ID") @RequestParam(name = "order_id", required = false)
          UUID orderId,
      @Parameter(description = "배송 상태") @RequestParam(name = "status", required = false)
          DeliveryStatus status,
      @Parameter(description = "출발 허브 ID")
          @RequestParam(name = "departure_hub_id", required = false)
          UUID departureHubId,
      @Parameter(description = "목적지 허브 ID")
          @RequestParam(name = "destination_hub_id", required = false)
          UUID destinationHubId,
      @Parameter(description = "배송지 주소(부분 일치)")
          @RequestParam(name = "address", required = false)
          @Size(max = 255)
          String address,
      @Parameter(description = "수령인(부분 일치)")
          @RequestParam(name = "recipient_name", required = false)
          @Size(max = 100)
          String recipientName,
      @Parameter(description = "업체 배송 담당 사용자 ID")
          @RequestParam(name = "delivery_manager_id", required = false)
          UUID deliveryManagerId,
      @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") @Min(0)
          int page,
      @Parameter(description = "페이지 크기(1~100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100)
          int size,
      @Parameter(description = "정렬 `created_at|updated_at,asc|desc`")
          @RequestParam(defaultValue = DeliverySort.DEFAULT)
          String sort) {
    DeliverySearchCondition condition =
        new DeliverySearchCondition(
            orderId,
            status,
            departureHubId,
            destinationHubId,
            address,
            recipientName,
            deliveryManagerId);
    Page<DeliveryResult> result =
        deliveryQueryService.search(
            condition, PageRequest.of(page, size, DeliverySort.parse(sort)));

    //TODO: 권한 검사하기
    /*

     */
    return new ApiResponse<>(
        new PageResponse<>(
            result.map(DeliveryResponse::from).getContent(),
            page,
            size,
            result.getTotalElements()));
  }

  @Operation(summary = "배송 정보 조회", description = "id에 해당하는 배송 정보를 조회합니다")
  @GetMapping("/{delivery_id}")
  ApiResponse<DeliveryResponse> searchDetail(
      @Parameter(description = "배송 ID") @PathVariable("delivery_id") UUID deliveryId) {
    //TODO: 권한 검사하기
    /*

     */
    return new ApiResponse<>(DeliveryResponse.from(deliveryQueryService.searchDetail(deliveryId)));
  }
}
