package com.notfound.logistics.orders.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.orders.application.dto.OrderOperationResult;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.presentation.dto.CreateOrderRequest;
import com.notfound.logistics.orders.presentation.dto.OrderDetailResponse;
import com.notfound.logistics.orders.presentation.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order", description = "주문 생성·조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
  private final OrderService service;

  @Operation(summary = "주문 생성", description = "멱등 키로 중복 생성을 방지하며 재고·배송 처리를 진행합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "최초 확정"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "종료된 기존 주문"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "202",
        description = "처리 또는 결과 확인 중"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "재고 부족 또는 멱등 키 충돌"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "502",
        description = "배송 생성 실패 확정")
  })
  @PostMapping
  public ResponseEntity<ApiResponse<OrderResponse>> create(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Valid @RequestBody CreateOrderRequest request,
      @Parameter(description = "요청별 UUID. 같은 주문 재시도 시 동일 값 사용", required = true)
          @RequestHeader("Idempotency-Key")
          UUID key) {
    return operation(service.create(request.toCommand(key), principal.getSubject()));
  }

  @Operation(summary = "주문 상세 조회", description = "MASTER 또는 주문 작성자가 주문 당시 정보를 조회합니다.")
  @GetMapping("/{id}")
  public ApiResponse<OrderDetailResponse> get(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
    return new ApiResponse<>(
        OrderDetailResponse.from(service.get(id, principal.getSubject(), isMaster(principal))));
  }

  private boolean isMaster(Jwt principal) {
    return "MASTER".equals(principal.getClaimAsString("role"));
  }

  @Operation(summary = "주문 목록·검색", description = "USER는 본인 주문, MASTER는 전체 주문을 검색합니다.")
  @GetMapping
  public ApiResponse<PageResponse<OrderDetailResponse>> search(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @RequestParam(name = "product_id", required = false) UUID productId,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(name = "created_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(name = "created_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "created_at,desc") String sort) {
    var result =
        service.search(
            productId,
            status,
            from,
            to,
            page,
            size,
            sort,
            principal.getSubject(),
            isMaster(principal));
    return new ApiResponse<>(
        new PageResponse<>(
            result.content().stream().map(OrderDetailResponse::from).toList(),
            result.page(),
            result.size(),
            result.totalElements()));
  }

  private ResponseEntity<ApiResponse<OrderResponse>> operation(OrderOperationResult result) {
    if (result.error() != null) throw new OrderBusinessException(result.error());
    var body = new ApiResponse<>(OrderResponse.from(result.order()));
    return switch (result.outcome()) {
      case CREATED ->
          ResponseEntity.created(URI.create("/api/orders/" + result.order().orderId())).body(body);
      case RETURNED -> ResponseEntity.ok(body);
      case IN_PROGRESS -> ResponseEntity.accepted().body(body);
      case REJECTED ->
          throw new IllegalStateException("Rejected order operation requires an error code");
    };
  }
}
