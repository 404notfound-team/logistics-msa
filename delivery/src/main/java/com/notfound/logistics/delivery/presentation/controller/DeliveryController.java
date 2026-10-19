package com.notfound.logistics.delivery.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.delivery.application.dto.DeliveryActor;
import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.application.service.DeliveryCommandService;
import com.notfound.logistics.delivery.application.service.DeliveryQueryService;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import com.notfound.logistics.delivery.presentation.dto.DeliveryModifyRequest;
import com.notfound.logistics.delivery.presentation.dto.DeliveryResponse;
import com.notfound.logistics.delivery.presentation.dto.DeliveryRouteResponse;
import com.notfound.logistics.delivery.presentation.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "배송", description = "허브 간 이동과 업체 배송")
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
class DeliveryController {
  private final DeliveryQueryService deliveryQueryService;
  private final DeliveryCommandService deliveryCommandService;

  @Operation(
      summary = "배송 목록 조회·검색",
      description = "MASTER·일반 USER는 전체, 배송 담당자는 본인 배정 배송에서 검색 조건에 맞는 항목을 조회합니다.")
  @GetMapping
  ApiResponse<PageResponse<DeliveryResponse>> search(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
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
          @RequestParam(name = "assignee_id", required = false)
          UUID assigneeId,
      @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") @Min(0)
          int page,
      @Parameter(description = "페이지 크기(1~100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100)
          int size,
      @Parameter(description = "정렬 `created_at|updated_at,asc|desc`")
          @RequestParam(defaultValue = DeliverySort.DEFAULT)
          String sort) {
    DeliverySearchCondition condition =
        new DeliverySearchCondition(
            orderId, status, departureHubId, destinationHubId, address, recipientName, assigneeId);
    Page<DeliveryResult> result =
        deliveryQueryService.search(
            condition, PageRequest.of(page, size, DeliverySort.parse(sort)), actor(principal));

    return new ApiResponse<>(
        new PageResponse<>(
            result.map(DeliveryResponse::from).getContent(),
            page,
            size,
            result.getTotalElements()));
  }

  @Operation(
      summary = "배송 정보 조회",
      description = "MASTER·일반 USER는 전체, 배송 담당자는 본인 배정 배송만 조회합니다. 타인·미배정 배송은 403입니다.")
  @GetMapping("/{delivery_id}")
  ApiResponse<DeliveryResponse> searchDetail(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Parameter(description = "배송 ID") @PathVariable("delivery_id") UUID deliveryId) {

    return new ApiResponse<>(
        DeliveryResponse.from(deliveryQueryService.searchDetail(deliveryId, actor(principal))));
  }

  @Operation(
      summary = "배송 경로 조회",
      description = "MASTER·일반 USER는 전체, 배송 담당자는 본인 배정 배송의 경로만 조회합니다. 타인·미배정 배송은 403입니다.")
  @GetMapping("/{id}/routes")
  ApiResponse<List<DeliveryRouteResponse>> searchRoutes(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Parameter(description = "배송 ID") @PathVariable() UUID id) {

    return new ApiResponse<>(
        deliveryQueryService.searchRoutes(id, actor(principal)).stream()
            .map(DeliveryRouteResponse::from)
            .toList());
  }

  @Operation(
      summary = "배송 정보 수정·수동 배정",
      description =
          "id에 해당하는 배송의 상태 또는 담당자를 수정합니다. 상태 변경은 MASTER 또는 해당 배송 담당자, 담당자 배정은 MASTER만 할 수 있습니다.")
  @PatchMapping("/{delivery_id}")
  ApiResponse<DeliveryResponse> modify(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Parameter(description = "배송 ID") @PathVariable("delivery_id") UUID deliveryId,
      @RequestBody DeliveryModifyRequest request) {
    DeliveryStatus status = request.status();
    UUID assigneeId = request.assigneeId();
    if (status == null && assigneeId == null) {
      throw new InvalidRequestException("$", "status 또는 assignee_id 중 하나 이상을 입력해야 합니다.");
    }
    return new ApiResponse<>(
        DeliveryResponse.from(
            deliveryCommandService.modify(deliveryId, status, assigneeId, actor(principal))));
  }

  // user_id·role·is_delivery_staff 클레임 형식은 JwtDecoder가 검증했다.
  private static DeliveryActor actor(Jwt principal) {
    return new DeliveryActor(
        UUID.fromString(principal.getClaimAsString("user_id")),
        "MASTER".equals(principal.getClaimAsString("role")),
        Boolean.TRUE.equals(principal.getClaim("is_delivery_staff")));
  }
}
